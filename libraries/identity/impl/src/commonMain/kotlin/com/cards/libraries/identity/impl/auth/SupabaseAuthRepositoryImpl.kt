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
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Apple
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.signInAnonymously
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.jan.supabase.auth.providers.OAuthProvider as SupabaseOAuthProvider
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Supabase-backed [AuthRepository]. Owns the user lifecycle + access
 * token end-to-end.
 *
 * Bootstrap (in `init`): waits for `supabase.auth.awaitInitialization()`
 * (so supabase-kt has loaded any persisted session), then resolves:
 *
 *   - [SessionStatus.Authenticated]      → emit [AuthState.Authenticated]
 *   - [SessionStatus.NotAuthenticated]   → signInAnonymously → recurse
 *   - [SessionStatus.Initializing]       → re-await (transient)
 *   - [SessionStatus.RefreshFailure]     → retry up to MaxBootstrapAttempts
 *
 * Mutex serializes session-mutating operations; [_state] is the source
 * of truth flipped under the lock. No in-flight sentinel exposed — the
 * shared flow only emits resolved values, and [current] suspends on
 * `.first()`.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AuthRepository::class)
@Inject
class SupabaseAuthRepositoryImpl(
    private val supabase: SupabaseClient,
    private val profileApi: ProfileApi,
    private val appEventBus: AppEventBus,
    appScope: AppCoroutineScope,
) : AuthRepository {

    private val logger = KLog.withTag("AuthRepository")
    private val mutex = Mutex()
    private val _state = MutableSharedFlow<AuthState>(replay = 1)
    private val sharedState: Flow<AuthState> = _state.asSharedFlow()

    init {
        appScope.launch {
            Catching { resolveBootstrap() }
                .logOnFailure { "Initial auth bootstrap failed; will retry via AuthRepository.retry()" }
        }
    }

    override suspend fun current(): AuthState =
        // .first() on a SharedFlow(replay=1) returns the latest value if
        // one has been emitted, or suspends until the first emission.
        sharedState.first()

    override fun observe(): Flow<AuthState> = sharedState

    override suspend fun accessToken(): String? {
        return when (current()) {
            is AuthState.Authenticated -> supabase.auth.currentSessionOrNull()?.accessToken
            is AuthState.Unauthenticated -> {
                logger.w { "Access token requested while Unauthenticated; request will go unauthed." }
                null
            }
        }
    }

    override suspend fun refreshAccessToken(): String? = Catching {
        supabase.auth.refreshCurrentSession()
        supabase.auth.currentSessionOrNull()?.accessToken
    }.logOnFailure { "Force refresh of access token failed" }.getOrNull()

    override suspend fun retry(): AuthState = mutex.withLock {
        // No-op if already authenticated.
        val latest = lastEmittedOrNull()
        if (latest is AuthState.Authenticated) return@withLock latest
        resolveBootstrapLocked()
    }

    /**
     * Core resolve. Recursive — defers to itself on transient states
     * (Initializing) or after triggering an anon sign-in. Bounded so we
     * don't spin forever; on hitting the cap we emit Unauthenticated
     * with the last cause.
     */
    private suspend fun resolveBootstrap() = mutex.withLock { resolveBootstrapLocked() }

    private suspend fun resolveBootstrapLocked(): AuthState {
        var lastCause: Throwable? = null
        repeat(MaxBootstrapAttempts) { attempt ->
            val outcome = Catching {
                supabase.auth.awaitInitialization()
                val status = supabase.auth.sessionStatus.value
                logger.d { "Bootstrap attempt ${attempt + 1}/$MaxBootstrapAttempts; status=${status::class.simpleName}" }
                when (status) {
                    is SessionStatus.Authenticated -> emitAuthenticatedFromSupabaseLocked()
                    is SessionStatus.NotAuthenticated -> {
                        supabase.auth.signInAnonymously()
                        // After signInAnonymously the session lands; loop continues
                        // to re-check status and emit Authenticated.
                        null
                    }
                    SessionStatus.Initializing,
                    is SessionStatus.RefreshFailure -> {
                        // Transient. supabase-kt is mid-refresh / mid-init; let
                        // the next iteration re-check. No backoff for now —
                        // these states resolve in milliseconds in practice.
                        null
                    }
                }
            }
            val state = outcome.getOrNull()
            if (state != null) return state
            outcome.exceptionOrNull()?.let { e ->
                lastCause = e
                logger.w(e) { "Bootstrap attempt ${attempt + 1} failed" }
            }
        }
        return emitUnauthenticatedLocked(cause = lastCause)
    }

    /**
     * Helper: read the Supabase session, build an [AuthState.Authenticated],
     * publish it. Assumes the lock is held + a valid session exists.
     */
    private suspend fun emitAuthenticatedFromSupabaseLocked(): AuthState.Authenticated {
        val session = supabase.auth.currentSessionOrNull()
            ?: error("emitAuthenticatedFromSupabaseLocked called without a session")
        val user = session.user
            ?: error("Supabase session has no user — should be impossible for an Authenticated status")
        val state = AuthState.Authenticated(
            userId = user.id,
            isAnonymous = isAnonymousFromSession(),
            email = user.email,
        )
        _state.emit(state)
        return state
    }

    private suspend fun emitUnauthenticatedLocked(cause: Throwable?): AuthState.Unauthenticated {
        val state = AuthState.Unauthenticated(cause = cause)
        _state.emit(state)
        return state
    }

    /**
     * supabase-kt exposes anonymous status indirectly: the user's
     * `aud` claim, or the `is_anonymous` claim on the JWT. We read it
     * via a heuristic on the session user — there's no first-class
     * field, but anonymous users have no email AND identities is empty.
     */
    private fun isAnonymousFromSession(): Boolean {
        val user = supabase.auth.currentSessionOrNull()?.user ?: return false
        // Anonymous users have no identity providers attached.
        return user.identities.isNullOrEmpty()
    }

    private fun lastEmittedOrNull(): AuthState? = _state.replayCache.firstOrNull()

    // ---------- Auth operations ----------

    override suspend fun signInWithEmail(email: String, password: String): SignInOutcome =
        mutex.withLock {
            Catching {
                supabase.auth.signInWith(Email) {
                    this.email = email
                    this.password = password
                }
                emitAuthenticatedFromSupabaseLocked()
            }.fold(
                onSuccess = { SignInOutcome.Success },
                onFailure = { e ->
                    when (e) {
                        is RestException -> mapSignInRestException(e, email)
                        is HttpRequestException -> SignInOutcome.NetworkError(e)
                        else -> SignInOutcome.Unknown(e)
                    }
                },
            )
        }

    override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome =
        mutex.withLock {
            Catching {
                supabase.auth.signUpWith(Email) {
                    this.email = email
                    this.password = password
                }
            }.fold(
                onSuccess = { SignUpOutcome.VerificationRequired(email) },
                onFailure = { e ->
                    when (e) {
                        is RestException -> mapSignUpRestException(e)
                        is HttpRequestException -> SignUpOutcome.NetworkError(e)
                        else -> SignUpOutcome.Unknown(e)
                    }
                },
            )
        }

    override suspend fun refreshSession(): RefreshOutcome = mutex.withLock {
        Catching {
            supabase.auth.refreshCurrentSession()
            val session = supabase.auth.currentSessionOrNull()
                ?: return@Catching RefreshOutcome.SessionExpired
            val emailConfirmed = session.user?.emailConfirmedAt != null
            if (emailConfirmed) {
                emitAuthenticatedFromSupabaseLocked()
                RefreshOutcome.EmailConfirmed
            } else {
                RefreshOutcome.StillPending
            }
        }.fold(
            onSuccess = { it },
            onFailure = { e ->
                when (e) {
                    is RestException ->
                        if (e.statusCode == 401 || e.statusCode == 403) RefreshOutcome.SessionExpired
                        else RefreshOutcome.Unknown(e)
                    is HttpRequestException -> RefreshOutcome.NetworkError(e)
                    else -> RefreshOutcome.Unknown(e)
                }
            },
        )
    }

    // Intentionally no mutex — resend doesn't mutate session state.
    override suspend fun resendVerificationEmail(email: String): ResendOutcome =
        Catching {
            supabase.auth.resendEmail(OtpType.Email.SIGNUP, email = email)
        }.fold(
            onSuccess = { ResendOutcome.Sent },
            onFailure = { e ->
                when (e) {
                    is RestException ->
                        if (e.statusCode == 429) ResendOutcome.RateLimited(retryAfterSeconds = null)
                        else ResendOutcome.Unknown(e)
                    is HttpRequestException -> ResendOutcome.NetworkError(e)
                    else -> ResendOutcome.Unknown(e)
                }
            },
        )

    override suspend fun signOut(): Unit = mutex.withLock {
        Catching { supabase.auth.signOut() }
            .logOnFailure { "Supabase signOut failed; clearing local state anyway" }
        emitUnauthenticatedLocked(cause = null)
        appEventBus.dispatch(AppEvent.SignedOut)
    }

    override suspend fun deleteAccount(): DeleteAccountOutcome = mutex.withLock {
        if (supabase.auth.currentSessionOrNull() == null) {
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
            Catching { supabase.auth.signOut() }
                .logOnFailure { "Supabase signOut after delete failed; clearing local state anyway" }
            emitUnauthenticatedLocked(cause = null)
            appEventBus.dispatch(AppEvent.SignedOut)
        }
        outcome
    }

    override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome =
        mutex.withLock {
            if (supabase.auth.currentSessionOrNull() == null) {
                return@withLock LinkIdentityOutcome.NotSignedIn
            }
            Catching {
                supabase.auth.linkIdentity(provider.toSupabase())
                emitAuthenticatedFromSupabaseLocked()
            }.fold(
                onSuccess = { LinkIdentityOutcome.Success },
                onFailure = { e ->
                    when (e) {
                        is RestException -> mapLinkRestException(e)
                        is HttpRequestException -> LinkIdentityOutcome.NetworkError(e)
                        else ->
                            if ((e.message ?: "").contains("cancel", ignoreCase = true)) {
                                LinkIdentityOutcome.Cancelled
                            } else LinkIdentityOutcome.Unknown(e)
                    }
                },
            )
        }

    override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome =
        mutex.withLock {
            Catching {
                supabase.auth.signInWith(provider.toSupabase())
                emitAuthenticatedFromSupabaseLocked()
            }.fold(
                onSuccess = { SignInOutcome.Success },
                onFailure = { e ->
                    when (e) {
                        is RestException -> mapOAuthSignInRestException(e)
                        is HttpRequestException -> SignInOutcome.NetworkError(e)
                        else ->
                            if ((e.message ?: "").contains("cancel", ignoreCase = true)) {
                                SignInOutcome.Cancelled
                            } else SignInOutcome.Unknown(e)
                    }
                },
            )
        }

    override suspend fun linkEmailIdentity(
        email: String,
        password: String,
    ): LinkEmailIdentityOutcome = mutex.withLock {
        if (supabase.auth.currentSessionOrNull() == null) {
            return@withLock LinkEmailIdentityOutcome.NotSignedIn
        }
        val current = lastEmittedOrNull()
        if (current is AuthState.Authenticated && !current.isAnonymous) {
            return@withLock LinkEmailIdentityOutcome.NotAnonymous
        }
        Catching {
            supabase.auth.updateUser {
                this.email = email
                this.password = password
            }
        }.fold(
            onSuccess = { LinkEmailIdentityOutcome.VerificationRequired(email) },
            onFailure = { e ->
                when (e) {
                    is RestException -> mapLinkEmailRestException(e)
                    is HttpRequestException -> LinkEmailIdentityOutcome.NetworkError(e)
                    else -> LinkEmailIdentityOutcome.Unknown(e)
                }
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
            msg.contains("password") &&
                (msg.contains("at least") || msg.contains("weak") || msg.contains("short")) ->
                LinkEmailIdentityOutcome.WeakPassword
            msg.contains("invalid email") || msg.contains("unable to validate email") ->
                LinkEmailIdentityOutcome.InvalidEmail
            else -> LinkEmailIdentityOutcome.Unknown(e)
        }
    }

    private fun OAuthProvider.toSupabase(): SupabaseOAuthProvider = when (this) {
        OAuthProvider.Google -> Google
        OAuthProvider.Apple -> Apple
    }

    private companion object {
        /**
         * Cap on recursive bootstrap iterations. Each iteration either
         * settles or transitions to a sign-in-anonymously call. In
         * practice we never see > 2 iterations; the cap is here for the
         * pathological case where supabase-kt keeps reporting Initializing
         * forever.
         */
        const val MaxBootstrapAttempts: Int = 5
    }
}
