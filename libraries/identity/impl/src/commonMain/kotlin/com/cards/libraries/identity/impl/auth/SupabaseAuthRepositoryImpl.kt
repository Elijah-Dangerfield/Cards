package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventBus
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.auth.AppleSignInCredential
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.DeleteAccountOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkEmailIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.RefreshOutcome
import com.dangerfield.cards.libraries.identity.auth.ResendOutcome
import com.dangerfield.cards.libraries.identity.auth.SendResetOutcome
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.auth.SignUpOutcome
import com.dangerfield.cards.libraries.identity.impl.ProfileApi
import com.dangerfield.cards.libraries.networking.AuthTokenInvalidator
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
 * **No account is created on launch.** On init this class resolves whatever
 * persisted session supabase-kt hydrates: a live session → [AuthState.Authenticated],
 * none → [AuthState.Unauthenticated]. It deliberately does *not* sign in
 * anonymously — the app stays session-less through onboarding and only mints an
 * account when the user finishes as a guest ([createGuestSession]) or signs in.
 * (This is what kills the orphan-anon-on-every-install problem the old
 * auto-anon bootstrap caused.)
 *
 * The resolve loop tolerates supabase-kt's transient hydration states
 * (Initializing / RefreshFailure) by re-polling a bounded number of times
 * before settling Unauthenticated; the offline→online connectivity observer
 * and explicit [retry] re-run it.
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
    private val profileApi: ProfileApi,
    private val appEventBus: AppEventBus,
    private val tokenInvalidator: AuthTokenInvalidator,
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
        logger.d { "retry: re-resolving session (latest=${latest?.let { it::class.simpleName } ?: "null"})" }
        resolveAndEmitLocked()
    }

    private suspend fun resolveAndEmit(): AuthState = mutex.withLock { resolveAndEmitLocked() }

    /**
     * Read the hydrated session and publish the matching [AuthState]. Loops
     * over supabase-kt's transient hydration states (Initializing /
     * RefreshFailure) up to [MaxResolveAttempts] before settling
     * Unauthenticated. **Never signs in** — a missing session resolves to
     * [AuthState.Unauthenticated]; an account is created only on an explicit
     * user action.
     */
    private suspend fun resolveAndEmitLocked(): AuthState {
        repeat(MaxResolveAttempts) {
            gateway.awaitInitialization()
            when (gateway.currentStatus()) {
                AuthGatewayStatus.Authenticated -> {
                    val session = gateway.currentSession()
                        ?: error("Authenticated status but no session in gateway")
                    val next = AuthState.Authenticated(
                        userId = session.userId,
                        isAnonymous = session.isAnonymous,
                        email = session.email,
                    )
                    state.emit(next)
                    logger.i {
                        "Emitted Authenticated(userId=${next.userId}, isAnonymous=${next.isAnonymous}, hasEmail=${next.email != null})"
                    }
                    return next
                }
                // No session and we no longer auto-create one — settle.
                AuthGatewayStatus.NotAuthenticated -> return emitUnauthenticatedLocked(cause = null)
                // Mid-hydration / transient refresh failure — re-poll.
                AuthGatewayStatus.Initializing,
                is AuthGatewayStatus.RefreshFailure -> Unit
            }
        }
        logger.w { "Resolve exhausted $MaxResolveAttempts attempts without settling" }
        return emitUnauthenticatedLocked(cause = null)
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
        // Drop the cached bearer before anyone observing this emission fires a
        // request — a session switch (e.g. anon → real via sign-in-with-Apple)
        // leaves the old token valid, so Ktor would otherwise keep sending it.
        tokenInvalidator.invalidate()
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
        // Sign-out / lost session — drop the cached bearer so no stale token
        // rides along on the next request.
        tokenInvalidator.invalidate()
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

    override suspend fun createGuestSession(): SignInOutcome = mutex.withLock {
        logger.d { "createGuestSession: signing in anonymously" }
        Catching {
            gateway.signInAnonymously()
            emitAuthenticatedFromGatewayLocked()
        }.fold(
            onSuccess = {
                logger.i { "createGuestSession: Success" }
                SignInOutcome.Success
            },
            onFailure = { e ->
                val outcome = when (e) {
                    is RestException -> mapOAuthSignInRestException(e)
                    is HttpRequestException -> SignInOutcome.NetworkError(e)
                    else -> SignInOutcome.Unknown(e)
                }
                logger.w(e) { "createGuestSession: ${outcome::class.simpleName}" }
                outcome
            },
        )
    }

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

    // Intentionally no mutex — reset-email doesn't mutate session state.
    override suspend fun sendPasswordResetEmail(email: String): SendResetOutcome {
        logger.d { "sendPasswordResetEmail: requesting" }
        return Catching {
            gateway.resetPasswordForEmail(email)
        }.fold(
            onSuccess = {
                logger.i { "sendPasswordResetEmail: Sent" }
                SendResetOutcome.Sent
            },
            onFailure = { e ->
                val outcome = when (e) {
                    is RestException ->
                        if (e.statusCode == 429) SendResetOutcome.RateLimited
                        else SendResetOutcome.Unknown(e)
                    is HttpRequestException -> SendResetOutcome.NetworkError(e)
                    else -> SendResetOutcome.Unknown(e)
                }
                logger.w(e) { "sendPasswordResetEmail: ${outcome::class.simpleName}" }
                outcome
            },
        )
    }

    override suspend fun signOut(): Unit = mutex.withLock {
        logger.i { "signOut: tearing down session" }
        Catching { gateway.signOut() }
            .logOnFailure { "Supabase signOut failed; clearing local state anyway" }
        // No session is created in its place — the user lands on the
        // logged-out landing page and must pick a method (guest / sign-in)
        // again. A later retry() just re-resolves (still Unauthenticated).
        emitUnauthenticatedLocked(cause = null)
        appEventBus.dispatch(AppEvent.SignedOut)
    }

    override suspend fun deleteAccount(): DeleteAccountOutcome = mutex.withLock {
        logger.d { "deleteAccount: attempting" }
        val session = gateway.currentSession()
        if (session == null) {
            logger.w { "deleteAccount: NotSignedIn (no supabase session)" }
            return@withLock DeleteAccountOutcome.NotSignedIn
        }
        // Anonymous accounts are deletable too — a guest's data (profile,
        // wallet, history) is a real server-side account and a user has the
        // right to erase it. The admin delete handles anon users fine.
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

    override suspend fun signInWithApple(credential: AppleSignInCredential): SignInOutcome =
        mutex.withLock {
            logger.d { "signInWithApple: attempting" }
            Catching {
                gateway.signInWithAppleIdToken(credential.identityToken, credential.nonce)
                emitAuthenticatedFromGatewayLocked()
            }.fold(
                onSuccess = {
                    logger.i { "signInWithApple: Success" }
                    SignInOutcome.Success
                },
                onFailure = { e ->
                    val outcome = mapNativeSignInFailure(e)
                    logger.w(e) { "signInWithApple: ${outcome::class.simpleName}" }
                    outcome
                },
            )
        }

    override suspend fun linkAppleIdentity(credential: AppleSignInCredential): LinkIdentityOutcome =
        mutex.withLock {
            logger.d { "linkAppleIdentity: attempting" }
            if (gateway.currentSession() == null) {
                logger.w { "linkAppleIdentity: NotSignedIn (no supabase session)" }
                return@withLock LinkIdentityOutcome.NotSignedIn
            }
            Catching {
                gateway.linkAppleIdToken(credential.identityToken, credential.nonce)
                // Linking attaches the Apple identity server-side but leaves the
                // local session/JWT stale (still is_anonymous=true, no
                // identities), so the app would keep showing "save your
                // progress". Refresh so the emitted state AND the bearer token
                // used for /v1/me reflect the now-claimed account.
                gateway.refreshSession()
                emitAuthenticatedFromGatewayLocked()
            }.fold(
                onSuccess = {
                    logger.i { "linkAppleIdentity: Success" }
                    LinkIdentityOutcome.Success
                },
                onFailure = { e ->
                    val outcome = when (e) {
                        is RestException ->
                            if (e.statusCode == 422) LinkIdentityOutcome.AlreadyOnAnotherAccount
                            else LinkIdentityOutcome.Unknown(e)
                        is HttpRequestException -> LinkIdentityOutcome.NetworkError(e)
                        else ->
                            if ((e.message ?: "").contains("cancel", ignoreCase = true)) {
                                LinkIdentityOutcome.Cancelled
                            } else LinkIdentityOutcome.Unknown(e)
                    }
                    logger.w(e) { "linkAppleIdentity: ${outcome::class.simpleName}" }
                    outcome
                },
            )
        }

    override suspend fun signInWithGoogleIdToken(idToken: String, nonce: String?): SignInOutcome =
        mutex.withLock {
            logger.d { "signInWithGoogleIdToken: attempting" }
            Catching {
                gateway.signInWithGoogleIdToken(idToken, nonce)
                emitAuthenticatedFromGatewayLocked()
            }.fold(
                onSuccess = {
                    logger.i { "signInWithGoogleIdToken: Success" }
                    SignInOutcome.Success
                },
                onFailure = { e ->
                    val outcome = mapNativeSignInFailure(e)
                    logger.w(e) { "signInWithGoogleIdToken: ${outcome::class.simpleName}" }
                    outcome
                },
            )
        }

    /** Shared failure mapping for the native id-token sign-in paths (Apple / Google). */
    private fun mapNativeSignInFailure(e: Throwable): SignInOutcome = when (e) {
        is RestException -> mapOAuthSignInRestException(e)
        is HttpRequestException -> SignInOutcome.NetworkError(e)
        else ->
            if ((e.message ?: "").contains("cancel", ignoreCase = true)) {
                SignInOutcome.Cancelled
            } else {
                SignInOutcome.Unknown(e)
            }
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

    private companion object {
        /** Bounded re-polls over supabase-kt's transient hydration states. */
        const val MaxResolveAttempts: Int = 5
    }
}
