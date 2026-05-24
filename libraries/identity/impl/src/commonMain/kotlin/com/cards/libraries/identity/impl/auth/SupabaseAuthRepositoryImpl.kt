package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventBus
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.DeleteAccountOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkEmailIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.RefreshOutcome
import com.dangerfield.cards.libraries.identity.auth.ResendOutcome
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.auth.SignUpOutcome
import com.dangerfield.cards.libraries.identity.impl.ProfileApi
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Supabase-backed [AuthRepository]. Owns the user lifecycle (sign-in /
 * sign-up / link / delete) and the public [AuthState] stream.
 *
 * The "is there a session — sign in anon if not" policy lives on
 * [AuthBootstrap]; this class waits on [AuthBootstrap.awaitResolved] to
 * derive its initial [AuthState], and calls [AuthBootstrap.invalidate]
 * after signOut / deleteAccount so the next consumer's resolve runs
 * fresh. Splitting that policy out is what breaks the construction-time
 * `NetworkClient → AuthRepository → ProfileApi → NetworkClient` cycle
 * the codebase used to dodge with a lazy provider — see the
 * [AuthBootstrap] header for the wiring.
 *
 * Mutex serializes session-mutating operations; [state] is the source
 * of truth flipped under the lock. No in-flight sentinel exposed — the
 * shared flow only emits resolved values, and [current] suspends on
 * `.first()`.
 *
 * [SupabaseAuthGateway] is the seam that makes this class unit-testable
 * without booting a real Supabase client. Production wires
 * [RealSupabaseAuthGateway]; tests use the in-memory fake in commonTest.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AuthRepository::class)
@Inject
class SupabaseAuthRepositoryImpl(
    private val gateway: SupabaseAuthGateway,
    private val authBootstrap: AuthBootstrap,
    private val profileApi: ProfileApi,
    private val appEventBus: AppEventBus,
    appScope: AppCoroutineScope,
) : AuthRepository {

    private val logger = KLog.withTag("AuthRepository")
    private val mutex = Mutex()
    private val state = MutableSharedFlow<AuthState>(replay = 1)

    init {
        logger.d { "init: awaiting initial bootstrap resolve" }
        appScope.launch {
            Catching { resolveAndEmit() }
                .logOnFailure { "Initial auth resolve failed; will retry via AuthRepository.retry()" }
        }
    }

    override suspend fun current(): AuthState =
        // .first() on a SharedFlow(replay=1) returns the latest value if
        // one has been emitted, or suspends until the first emission.
        // No log — this is the hot read path.
        state.first()

    override fun observe(): Flow<AuthState> = state

    override suspend fun retry(): AuthState = mutex.withLock {
        val latest = lastEmittedOrNull()
        if (latest is AuthState.Authenticated) {
            logger.d { "retry: already Authenticated, no-op" }
            return@withLock latest
        }
        logger.d { "retry: invalidating bootstrap + re-resolving (latest=${latest?.let { it::class.simpleName } ?: "null"})" }
        authBootstrap.invalidate()
        resolveAndEmitLocked()
    }

    private suspend fun resolveAndEmit(): AuthState = mutex.withLock { resolveAndEmitLocked() }

    private suspend fun resolveAndEmitLocked(): AuthState {
        val outcome = authBootstrap.awaitResolved()
        return when (outcome) {
            is BootstrapOutcome.Authenticated -> {
                val next = AuthState.Authenticated(
                    userId = outcome.userId,
                    isAnonymous = outcome.isAnonymous,
                    email = outcome.email,
                )
                state.emit(next)
                logger.i {
                    "Emitted Authenticated(userId=${next.userId}, isAnonymous=${next.isAnonymous}, hasEmail=${next.email != null})"
                }
                next
            }
            is BootstrapOutcome.Failed -> emitUnauthenticatedLocked(cause = outcome.cause)
        }
    }

    /**
     * Helper: read the gateway session, build an [AuthState.Authenticated],
     * publish it. Assumes the lock is held + a valid session exists.
     */
    private suspend fun emitAuthenticatedFromGatewayLocked(): AuthState.Authenticated {
        val session = gateway.currentSession()
            ?: error("emitAuthenticatedFromGatewayLocked called without a session")
        val next = AuthState.Authenticated(
            userId = session.userId,
            isAnonymous = session.isAnonymous,
            // Anonymous users don't have a real email for our purposes; the
            // gateway already nulls supabase's placeholder address.
            email = session.email,
        )
        state.emit(next)
        // Info-level so this lands in production diagnostic dumps — auth
        // state transitions are the load-bearing observability moment.
        logger.i {
            "Emitted Authenticated(userId=${next.userId}, isAnonymous=${next.isAnonymous}, hasEmail=${next.email != null})"
        }
        return next
    }

    private suspend fun emitUnauthenticatedLocked(cause: Throwable?): AuthState.Unauthenticated {
        val next = AuthState.Unauthenticated(cause = cause)
        state.emit(next)
        if (cause != null) {
            logger.w(cause) { "Emitted Unauthenticated with cause" }
        } else {
            logger.i { "Emitted Unauthenticated (no cause — sign-out or exhausted resolve)" }
        }
        return next
    }

    private fun lastEmittedOrNull(): AuthState? = state.replayCache.firstOrNull()

    // ---------- Auth operations ----------

    override suspend fun signInWithEmail(email: String, password: String): SignInOutcome =
        mutex.withLock {
            logger.d { "signInWithEmail: attempting" }
            Catching {
                gateway.signInWithEmail(email, password)
                emitAuthenticatedFromGatewayLocked()
            }.fold(
                onSuccess = {
                    logger.i { "signInWithEmail: Success" }
                    SignInOutcome.Success
                },
                onFailure = { e ->
                    val outcome = when (e) {
                        is RestException -> mapSignInRestException(e, email)
                        is HttpRequestException -> SignInOutcome.NetworkError(e)
                        else -> SignInOutcome.Unknown(e)
                    }
                    logger.w(e) { "signInWithEmail: ${outcome::class.simpleName}" }
                    outcome
                },
            )
        }

    override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome =
        mutex.withLock {
            logger.d { "signUpWithEmail: attempting" }
            Catching {
                gateway.signUpWithEmail(email, password)
            }.fold(
                onSuccess = {
                    logger.i { "signUpWithEmail: VerificationRequired" }
                    SignUpOutcome.VerificationRequired(email)
                },
                onFailure = { e ->
                    val outcome = when (e) {
                        is RestException -> mapSignUpRestException(e)
                        is HttpRequestException -> SignUpOutcome.NetworkError(e)
                        else -> SignUpOutcome.Unknown(e)
                    }
                    logger.w(e) { "signUpWithEmail: ${outcome::class.simpleName}" }
                    outcome
                },
            )
        }

    override suspend fun refreshSession(): RefreshOutcome = mutex.withLock {
        logger.d { "refreshSession: forcing gateway session refresh" }
        Catching {
            gateway.refreshSession()
            val session = gateway.currentSession()
                ?: return@Catching RefreshOutcome.SessionExpired
            if (session.isEmailConfirmed) {
                emitAuthenticatedFromGatewayLocked()
                RefreshOutcome.EmailConfirmed
            } else {
                RefreshOutcome.StillPending
            }
        }.fold(
            onSuccess = { outcome ->
                logger.i { "refreshSession: ${outcome::class.simpleName}" }
                outcome
            },
            onFailure = { e ->
                val outcome = when (e) {
                    is RestException ->
                        if (e.statusCode == 401 || e.statusCode == 403) RefreshOutcome.SessionExpired
                        else RefreshOutcome.Unknown(e)
                    is HttpRequestException -> RefreshOutcome.NetworkError(e)
                    else -> RefreshOutcome.Unknown(e)
                }
                logger.w(e) { "refreshSession: ${outcome::class.simpleName}" }
                outcome
            },
        )
    }

    // Intentionally no mutex — resend doesn't mutate session state.
    override suspend fun resendVerificationEmail(email: String): ResendOutcome {
        logger.d { "resendVerificationEmail: requesting" }
        return Catching {
            gateway.resendVerificationEmail(email)
        }.fold(
            onSuccess = {
                logger.i { "resendVerificationEmail: Sent" }
                ResendOutcome.Sent
            },
            onFailure = { e ->
                val outcome = when (e) {
                    is RestException ->
                        if (e.statusCode == 429) ResendOutcome.RateLimited(retryAfterSeconds = null)
                        else ResendOutcome.Unknown(e)
                    is HttpRequestException -> ResendOutcome.NetworkError(e)
                    else -> ResendOutcome.Unknown(e)
                }
                logger.w(e) { "resendVerificationEmail: ${outcome::class.simpleName}" }
                outcome
            },
        )
    }

    override suspend fun signOut(): Unit = mutex.withLock {
        logger.i { "signOut: tearing down session" }
        Catching { gateway.signOut() }
            .logOnFailure { "Supabase signOut failed; clearing local state anyway" }
        // Invalidate the bootstrap so a subsequent retry() runs a fresh
        // resolve (which will anon-sign-in unless the user signs in /
        // signs up first).
        authBootstrap.invalidate()
        emitUnauthenticatedLocked(cause = null)
        appEventBus.dispatch(AppEvent.SignedOut)
    }

    override suspend fun deleteAccount(): DeleteAccountOutcome = mutex.withLock {
        logger.d { "deleteAccount: attempting" }
        if (gateway.currentSession() == null) {
            logger.w { "deleteAccount: NotSignedIn (no supabase session)" }
            return@withLock DeleteAccountOutcome.NotSignedIn
        }
        val outcome = Catching { profileApi.deleteMe() }.fold(
            onSuccess = { response ->
                when (response.status.value) {
                    204, 200, 404 -> DeleteAccountOutcome.Success
                    401 -> DeleteAccountOutcome.NotSignedIn
                    503 -> DeleteAccountOutcome.NotConfigured
                    else -> DeleteAccountOutcome.Unknown(
                        IllegalStateException("Unexpected status ${response.status.value}"),
                    )
                }
            },
            onFailure = { e ->
                when (e) {
                    is io.ktor.client.plugins.ClientRequestException -> when (e.response.status.value) {
                        401 -> DeleteAccountOutcome.NotSignedIn
                        else -> DeleteAccountOutcome.Unknown(e)
                    }
                    is io.ktor.client.plugins.ServerResponseException ->
                        if (e.response.status.value == 503) DeleteAccountOutcome.NotConfigured
                        else DeleteAccountOutcome.Unknown(e)
                    else -> DeleteAccountOutcome.NetworkError(e)
                }
            },
        )

        if (outcome is DeleteAccountOutcome.Success) {
            logger.i { "deleteAccount: Success — signing out + dispatching SignedOut" }
            Catching { gateway.signOut() }
                .logOnFailure { "Supabase signOut after delete failed; clearing local state anyway" }
            authBootstrap.invalidate()
            emitUnauthenticatedLocked(cause = null)
            appEventBus.dispatch(AppEvent.SignedOut)
        } else {
            logger.w { "deleteAccount: ${outcome::class.simpleName}" }
        }
        outcome
    }

    override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome =
        mutex.withLock {
            logger.d { "linkOAuthIdentity: attempting with $provider" }
            if (gateway.currentSession() == null) {
                logger.w { "linkOAuthIdentity: NotSignedIn (no supabase session)" }
                return@withLock LinkIdentityOutcome.NotSignedIn
            }
            Catching {
                gateway.linkOAuthIdentity(provider)
                emitAuthenticatedFromGatewayLocked()
            }.fold(
                onSuccess = {
                    logger.i { "linkOAuthIdentity($provider): Success" }
                    LinkIdentityOutcome.Success
                },
                onFailure = { e ->
                    val outcome = when (e) {
                        is RestException -> mapLinkRestException(e)
                        is HttpRequestException -> LinkIdentityOutcome.NetworkError(e)
                        else ->
                            if ((e.message ?: "").contains("cancel", ignoreCase = true)) {
                                LinkIdentityOutcome.Cancelled
                            } else LinkIdentityOutcome.Unknown(e)
                    }
                    logger.w(e) { "linkOAuthIdentity($provider): ${outcome::class.simpleName}" }
                    outcome
                },
            )
        }

    override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome =
        mutex.withLock {
            logger.d { "signInWithOAuth: attempting with $provider" }
            Catching {
                gateway.signInWithOAuth(provider)
                emitAuthenticatedFromGatewayLocked()
            }.fold(
                onSuccess = {
                    logger.i { "signInWithOAuth($provider): Success" }
                    SignInOutcome.Success
                },
                onFailure = { e ->
                    val outcome = when (e) {
                        is RestException -> mapOAuthSignInRestException(e)
                        is HttpRequestException -> SignInOutcome.NetworkError(e)
                        else ->
                            if ((e.message ?: "").contains("cancel", ignoreCase = true)) {
                                SignInOutcome.Cancelled
                            } else SignInOutcome.Unknown(e)
                    }
                    logger.w(e) { "signInWithOAuth($provider): ${outcome::class.simpleName}" }
                    outcome
                },
            )
        }

    override suspend fun linkEmailIdentity(
        email: String,
        password: String,
    ): LinkEmailIdentityOutcome = mutex.withLock {
        logger.d { "linkEmailIdentity: attempting" }
        if (gateway.currentSession() == null) {
            logger.w { "linkEmailIdentity: NotSignedIn (no supabase session)" }
            return@withLock LinkEmailIdentityOutcome.NotSignedIn
        }
        val current = lastEmittedOrNull()
        if (current is AuthState.Authenticated && !current.isAnonymous) {
            logger.w { "linkEmailIdentity: NotAnonymous — guarded against email-change on a real account" }
            return@withLock LinkEmailIdentityOutcome.NotAnonymous
        }
        Catching {
            gateway.linkEmailIdentity(email, password)
        }.fold(
            onSuccess = {
                logger.i { "linkEmailIdentity: VerificationRequired" }
                LinkEmailIdentityOutcome.VerificationRequired(email)
            },
            onFailure = { e ->
                val outcome = when (e) {
                    is RestException -> mapLinkEmailRestException(e)
                    is HttpRequestException -> LinkEmailIdentityOutcome.NetworkError(e)
                    else -> LinkEmailIdentityOutcome.Unknown(e)
                }
                logger.w(e) { "linkEmailIdentity: ${outcome::class.simpleName}" }
                outcome
            },
        )
    }

    // ---------- Mappers ----------

    private fun mapSignInRestException(e: RestException, email: String): SignInOutcome {
        val msg = (e.message ?: "").lowercase()
        return when {
            e.statusCode == 400 && msg.contains("invalid login credentials") ->
                SignInOutcome.InvalidCredentials
            e.statusCode == 400 && msg.contains("email not confirmed") ->
                SignInOutcome.EmailNotConfirmed(email)
            else -> SignInOutcome.Unknown(e)
        }
    }

    private fun mapSignUpRestException(e: RestException): SignUpOutcome {
        val msg = (e.message ?: "").lowercase()
        return when {
            msg.contains("user already registered") ||
                (msg.contains("email address") && msg.contains("already")) ->
                SignUpOutcome.EmailAlreadyRegistered
            msg.contains("password") && msg.contains("at least") ->
                SignUpOutcome.WeakPassword
            msg.contains("invalid email") || msg.contains("unable to validate email") ->
                SignUpOutcome.InvalidEmail
            else -> SignUpOutcome.Unknown(e)
        }
    }

    private fun mapLinkRestException(e: RestException): LinkIdentityOutcome {
        val msg = (e.message ?: "").lowercase()
        return when {
            e.statusCode == 422 && msg.contains("already") ->
                LinkIdentityOutcome.AlreadyOnAnotherAccount
            e.statusCode == 400 && msg.contains("provider") && msg.contains("disabled") ->
                LinkIdentityOutcome.ProviderNotEnabled
            else -> LinkIdentityOutcome.Unknown(e)
        }
    }

    private fun mapOAuthSignInRestException(e: RestException): SignInOutcome {
        val msg = (e.message ?: "").lowercase()
        return when {
            e.statusCode == 400 && msg.contains("provider") && msg.contains("disabled") ->
                SignInOutcome.ProviderNotEnabled
            else -> SignInOutcome.Unknown(e)
        }
    }

    private fun mapLinkEmailRestException(e: RestException): LinkEmailIdentityOutcome {
        val msg = (e.message ?: "").lowercase()
        return when {
            msg.contains("user already registered") ||
                (msg.contains("email") && msg.contains("already")) ||
                (msg.contains("email") && msg.contains("taken")) ->
                LinkEmailIdentityOutcome.EmailAlreadyRegistered
            else -> LinkEmailIdentityOutcome.Unknown(e)
        }
    }
}
