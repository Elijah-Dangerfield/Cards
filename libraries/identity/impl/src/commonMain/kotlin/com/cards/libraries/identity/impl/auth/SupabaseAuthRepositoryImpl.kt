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
 * On construction, the resolve loop runs once: it waits for
 * `supabase.auth.awaitInitialization()` (so supabase-kt has loaded any
 * persisted session), inspects [SessionStatus], and either settles on
 * an [AuthState] or advances supabase-kt's state (signing in anon when
 * unauthenticated) before re-polling. See [resolveLocked] for the
 * branch table.
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
        logger.d { "init: kicking off initial resolve" }
        appScope.launch {
            Catching { resolve() }
                .logOnFailure { "Initial auth resolve failed; will retry via AuthRepository.retry()" }
        }
    }

    override suspend fun current(): AuthState =
        // .first() on a SharedFlow(replay=1) returns the latest value if
        // one has been emitted, or suspends until the first emission.
        // No log — this is the hot read path.
        sharedState.first()

    override fun observe(): Flow<AuthState> = sharedState

    override suspend fun accessToken(): String? {
        return when (val state = current()) {
            is AuthState.Authenticated -> {
                val token = supabase.auth.currentSessionOrNull()?.accessToken
                if (token == null) {
                    // Should be impossible — we're Authenticated but no session?
                    logger.w { "accessToken: Authenticated state but no session in supabase-kt for ${state.userId}" }
                }
                token
            }
            is AuthState.Unauthenticated -> {
                logger.w { "accessToken: requested while Unauthenticated; request will go unauthed" }
                null
            }
        }
    }

    override suspend fun refreshAccessToken(): String? {
        logger.d { "refreshAccessToken: forcing supabase session refresh" }
        return Catching {
            supabase.auth.refreshCurrentSession()
            val token = supabase.auth.currentSessionOrNull()?.accessToken
            logger.d { "refreshAccessToken: ${if (token != null) "got fresh token" else "no session after refresh"}" }
            token
        }.logOnFailure { "Force refresh of access token failed" }.getOrNull()
    }

    override suspend fun retry(): AuthState = mutex.withLock {
        // No-op if already authenticated.
        val latest = lastEmittedOrNull()
        if (latest is AuthState.Authenticated) {
            logger.d { "retry: already Authenticated, no-op" }
            return@withLock latest
        }
        logger.d { "retry: re-running resolve loop (latest=${latest?.let { it::class.simpleName } ?: "null"})" }
        resolveLocked()
    }

    private suspend fun resolve() = mutex.withLock { resolveLocked() }

    /**
     * The resolve loop. Each iteration calls [resolveOnceLocked] and acts
     * on the [ResolveStep]:
     *  - [ResolveStep.Settled] → return the resolved state.
     *  - [ResolveStep.Advanced] → we mutated supabase-kt's state (anon
     *    sign-in); loop to pick up the new status.
     *  - [ResolveStep.Transient] → supabase-kt is mid-init or mid-refresh;
     *    loop to re-poll. No backoff (these settle in ms in practice).
     *
     * On an exception we fail fast — calling `signInAnonymously()` again
     * after it threw would just throw the same way, so retrying without
     * backoff is pure waste. The caller gets [AuthState.Unauthenticated]
     * with the cause and can re-attempt explicitly via [retry].
     *
     * [MaxResolveAttempts] bounds the pathological case where supabase-kt
     * keeps reporting a transient status. In practice we see ≤ 2 iters.
     */
    private suspend fun resolveLocked(): AuthState {
        repeat(MaxResolveAttempts) { attempt ->
            val step = Catching { resolveOnceLocked(attempt) }
                .fold(onSuccess = { it }, onFailure = { ResolveStep.Failed(it) })
            when (step) {
                is ResolveStep.Settled -> return step.state
                is ResolveStep.Advanced, ResolveStep.Transient -> Unit // loop
                is ResolveStep.Failed -> {
                    logger.w(step.cause) { "Auth resolve failed at attempt ${attempt + 1}" }
                    return emitUnauthenticatedLocked(cause = step.cause)
                }
            }
        }
        // Exhausted the loop with only Transient/Advanced steps and never
        // landed on Authenticated. Treat as Unauthenticated with no cause —
        // we never saw an error, supabase-kt just never settled.
        return emitUnauthenticatedLocked(cause = null)
    }

    /**
     * Single resolve pass. Returns a [ResolveStep] describing what
     * happened; doesn't loop. Exceptions propagate to [resolveLocked]'s
     * Catching wrapper.
     */
    private suspend fun resolveOnceLocked(attempt: Int): ResolveStep {
        supabase.auth.awaitInitialization()
        val status = supabase.auth.sessionStatus.value
        logger.d { "Resolve attempt ${attempt + 1}/$MaxResolveAttempts; status=${status::class.simpleName}" }
        return when (status) {
            is SessionStatus.Authenticated -> ResolveStep.Settled(emitAuthenticatedFromSupabaseLocked())
            is SessionStatus.NotAuthenticated -> {
                supabase.auth.signInAnonymously()
                ResolveStep.Advanced
            }
            SessionStatus.Initializing,
            is SessionStatus.RefreshFailure -> ResolveStep.Transient
        }
    }

    /**
     * What a single resolve pass produced. The three "loop again" cases
     * (Advanced vs Transient) are named instead of crammed into a nullable
     * return so the loop's intent is readable.
     */
    private sealed interface ResolveStep {
        /** Landed on a session — we're done. */
        data class Settled(val state: AuthState.Authenticated) : ResolveStep
        /** We mutated supabase-kt's state (signed in anon); loop to re-poll. */
        data object Advanced : ResolveStep
        /** supabase-kt is mid-init or mid-refresh; loop to re-poll. */
        data object Transient : ResolveStep
        /** An exception escaped the pass — caller should NOT retry. */
        data class Failed(val cause: Throwable) : ResolveStep
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
        // Info-level so this lands in production diagnostic dumps — auth
        // state transitions are the load-bearing observability moment.
        logger.i {
            "Emitted Authenticated(userId=${state.userId}, isAnonymous=${state.isAnonymous}, hasEmail=${state.email != null})"
        }
        return state
    }

    private suspend fun emitUnauthenticatedLocked(cause: Throwable?): AuthState.Unauthenticated {
        val state = AuthState.Unauthenticated(cause = cause)
        _state.emit(state)
        if (cause != null) {
            logger.w(cause) { "Emitted Unauthenticated with cause" }
        } else {
            logger.i { "Emitted Unauthenticated (no cause — sign-out or exhausted resolve)" }
        }
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
            logger.d { "signInWithEmail: attempting" }
            Catching {
                supabase.auth.signInWith(Email) {
                    this.email = email
                    this.password = password
                }
                emitAuthenticatedFromSupabaseLocked()
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
                supabase.auth.signUpWith(Email) {
                    this.email = email
                    this.password = password
                }
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
        logger.d { "refreshSession: forcing supabase session refresh" }
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
            supabase.auth.resendEmail(OtpType.Email.SIGNUP, email = email)
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
        Catching { supabase.auth.signOut() }
            .logOnFailure { "Supabase signOut failed; clearing local state anyway" }
        emitUnauthenticatedLocked(cause = null)
        appEventBus.dispatch(AppEvent.SignedOut)
    }

    override suspend fun deleteAccount(): DeleteAccountOutcome = mutex.withLock {
        logger.d { "deleteAccount: attempting" }
        if (supabase.auth.currentSessionOrNull() == null) {
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
            Catching { supabase.auth.signOut() }
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
            if (supabase.auth.currentSessionOrNull() == null) {
                logger.w { "linkOAuthIdentity: NotSignedIn (no supabase session)" }
                return@withLock LinkIdentityOutcome.NotSignedIn
            }
            Catching {
                supabase.auth.linkIdentity(provider.toSupabase())
                emitAuthenticatedFromSupabaseLocked()
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
                supabase.auth.signInWith(provider.toSupabase())
                emitAuthenticatedFromSupabaseLocked()
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
        if (supabase.auth.currentSessionOrNull() == null) {
            logger.w { "linkEmailIdentity: NotSignedIn (no supabase session)" }
            return@withLock LinkEmailIdentityOutcome.NotSignedIn
        }
        val current = lastEmittedOrNull()
        if (current is AuthState.Authenticated && !current.isAnonymous) {
            logger.w { "linkEmailIdentity: NotAnonymous — guarded against email-change on a real account" }
            return@withLock LinkEmailIdentityOutcome.NotAnonymous
        }
        Catching {
            supabase.auth.updateUser {
                this.email = email
                this.password = password
            }
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
         * Cap on resolve-loop iterations. Each iteration either settles
         * or advances supabase-kt's state. In practice we never see > 2
         * iterations; the cap is here for the pathological case where
         * supabase-kt keeps reporting Initializing / RefreshFailure forever.
         */
        const val MaxResolveAttempts: Int = 5
    }
}
