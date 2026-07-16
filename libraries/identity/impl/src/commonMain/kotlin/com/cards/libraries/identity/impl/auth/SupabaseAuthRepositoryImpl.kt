package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventBus
import com.dangerfield.cards.libraries.cards.UserScopedDataReset
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
import com.dangerfield.cards.libraries.networking.SessionRejectionBus
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes
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
    private val userScopedDataReset: UserScopedDataReset,
    private val tokenInvalidator: AuthTokenInvalidator,
    private val sessionRejectionBus: SessionRejectionBus,
    appScope: AppCoroutineScope,
) : AuthRepository {

    private val logger = KLog.withTag("AuthRepository")
    private val mutex = Mutex()
    private val state = MutableSharedFlow<AuthState>(replay = 1)

    /**
     * The single in-flight browser-OAuth attempt, or null when none is pending.
     *
     * supabase-kt's `signInWith(OAuth)` / `linkIdentity(OAuth)` only *open* the
     * system browser and return immediately — the real result arrives ~seconds
     * later as the `cards://login-callback` deep link, which the app feeds to
     * [completeOAuthRedirect]. To keep the outcome-driven UI working (the VMs
     * `when` on the return value), the start call parks here and *awaits* this
     * deferred; [completeOAuthRedirect] resolves it once the redirect lands.
     *
     * [kind] tells the redirect handler how to finish: a SignIn redirect carries
     * a session to parse+import; a Link redirect carries none and instead needs
     * the existing session refreshed to fold in the just-attached identity.
     *
     * Guarded by [mutex] — only ever read/written under the lock.
     */
    private var pendingOAuth: PendingOAuth? = null

    private class PendingOAuth(
        val kind: OAuthFlowKind,
        val result: CompletableDeferred<OAuthRedirectResult>,
    )

    private enum class OAuthFlowKind { SignIn, Link }

    /**
     * What [completeOAuthRedirect] hands back to the parked starter. The starter
     * maps it to the right `*Outcome` for its flow (sign-in vs link).
     */
    private sealed interface OAuthRedirectResult {
        data object Success : OAuthRedirectResult
        data object Cancelled : OAuthRedirectResult
        data class Failed(val cause: Throwable) : OAuthRedirectResult
    }

    init {
        logger.d { "init: awaiting initial bootstrap resolve" }
        appScope.launch {
            Catching { resolveAndEmit() }
                .logOnFailure { "Initial auth resolve failed; will retry via AuthRepository.retry()" }
        }
        // The token layer signals here when the auth server definitively rejects
        // our session (a refresh the server itself rejected). Tear it down so the
        // app stops re-sending the dead token and can route the user to re-auth.
        appScope.launch {
            sessionRejectionBus.rejections.collect { rejection ->
                Catching { onSessionRejected(rejection.wasAnonymous) }
                    .logOnFailure { "Session-rejection teardown failed" }
            }
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
                    if (session == null) {
                        // Authenticated tokens are present but the session has no
                        // user yet — a tokens-only state from an OAuth import or a
                        // storage load. Fetch the user and re-poll rather than
                        // crash; the user must never get stuck on a transient
                        // hydration gap. If hydration keeps failing we fall through
                        // to settle Unauthenticated below.
                        logger.w { "Authenticated but session not hydrated — fetching user, then retrying" }
                        Catching { gateway.hydrateCurrentUser() }
                            .logOnFailure { "User hydration during resolve failed; will retry" }
                        return@repeat
                    }
                    val next = AuthState.Authenticated(
                        userId = session.userId,
                        isAnonymous = session.isAnonymous,
                        email = session.email,
                    )
                    emitLocked(next)
                    logger.i {
                        "Emitted Authenticated(userId=${next.userId}, isAnonymous=${next.isAnonymous}, hasEmail=${next.email != null})"
                    }
                    return next
                }
                // No session and we no longer auto-create one — settle.
                AuthGatewayStatus.NotAuthenticated -> return settleUnauthenticatedLocked()
                // Mid-hydration / transient refresh failure — re-poll.
                AuthGatewayStatus.Initializing,
                is AuthGatewayStatus.RefreshFailure -> Unit
            }
        }
        logger.w { "Resolve exhausted $MaxResolveAttempts attempts without settling" }
        return settleUnauthenticatedLocked()
    }

    /**
     * Settle a resolve that found no session. [AuthState.Unauthenticated.Reason.SessionExpired]
     * is sticky within the run: a re-resolve (connectivity flip, healer retry,
     * SessionExpired-screen retry) that still finds nothing keeps the reason —
     * and its [AuthState.Unauthenticated.wasAnonymous] — instead of decaying to
     * `None`, which would make identity self-heal read the dead session as an
     * ordinary stranding and mint a fresh guest over it (AUTH-19).
     */
    private suspend fun settleUnauthenticatedLocked(): AuthState.Unauthenticated {
        val last = lastEmittedOrNull() as? AuthState.Unauthenticated
        return if (last?.reason == AuthState.Unauthenticated.Reason.SessionExpired) {
            emitUnauthenticatedLocked(
                cause = null,
                reason = AuthState.Unauthenticated.Reason.SessionExpired,
                wasAnonymous = last.wasAnonymous,
            )
        } else {
            emitUnauthenticatedLocked(cause = null)
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
        // Drop the cached bearer before anyone observing this emission fires a
        // request — a session switch (e.g. anon → real via sign-in-with-Apple)
        // leaves the old token valid, so Ktor would otherwise keep sending it.
        tokenInvalidator.invalidate()
        emitLocked(next)
        // Info-level so this lands in production diagnostic dumps — auth
        // state transitions are the load-bearing observability moment.
        logger.i {
            "Emitted Authenticated(userId=${next.userId}, isAnonymous=${next.isAnonymous}, hasEmail=${next.email != null})"
        }
        return next
    }

    private suspend fun emitUnauthenticatedLocked(
        cause: Throwable?,
        reason: AuthState.Unauthenticated.Reason = AuthState.Unauthenticated.Reason.None,
        wasAnonymous: Boolean = false,
    ): AuthState.Unauthenticated {
        val next = AuthState.Unauthenticated(cause = cause, reason = reason, wasAnonymous = wasAnonymous)
        // Sign-out / lost session — drop the cached bearer so no stale token
        // rides along on the next request.
        tokenInvalidator.invalidate()
        emitLocked(next)
        when {
            cause != null -> logger.w(cause) { "Emitted Unauthenticated (reason=$reason)" }
            else -> logger.i { "Emitted Unauthenticated (reason=$reason)" }
        }
        return next
    }

    /**
     * The persisted session is gone and [GuestSessionHealer]'s retry couldn't
     * revive it, while a cached server profile proves an account exists — the
     * client-declared twin of a server rejection. Same teardown: the app routes
     * to recovery instead of the healer minting over the stranded account.
     */
    override suspend fun markSessionUnrecoverable(wasAnonymous: Boolean) {
        logger.w { "Session declared unrecoverable by identity self-heal (wasAnonymous=$wasAnonymous)" }
        onSessionRejected(wasAnonymous)
    }

    /**
     * The session is definitively dead — the auth server rejected it (token
     * revoked, account deleted, refresh token invalid) or the client declared it
     * unrecoverable ([markSessionUnrecoverable]). Tear it down: clear the
     * supabase session so the bearer stops re-attaching a dead token, then emit
     * [AuthState.Unauthenticated.Reason.SessionExpired] so the app can route the
     * user to re-auth (claimed) or a fresh start (guest).
     *
     * Idempotent: a burst of rejected requests collapses to one teardown.
     */
    private suspend fun onSessionRejected(wasAnonymous: Boolean): Unit = mutex.withLock {
        val latest = lastEmittedOrNull()
        if (latest is AuthState.Unauthenticated &&
            latest.reason == AuthState.Unauthenticated.Reason.SessionExpired
        ) {
            logger.d { "onSessionRejected: already SessionExpired — no-op" }
            return@withLock
        }
        logger.w { "Session rejected by auth server — tearing down (wasAnonymous=$wasAnonymous)" }
        // Clear the dead supabase session so currentSession() goes null and the
        // bearer stops re-attaching the rejected token.
        Catching { gateway.signOut() }
            .logOnFailure { "Gateway signOut during session-rejection cleanup failed; clearing local state anyway" }
        emitUnauthenticatedLocked(
            cause = null,
            reason = AuthState.Unauthenticated.Reason.SessionExpired,
            wasAnonymous = wasAnonymous,
        )
    }

    /**
     * The single choke point through which every [AuthState] emission flows.
     * Owning the transition here is what makes user-scoped data handling
     * un-forgettable: any current/future sign-in, switch, sign-out, or delete
     * path lands here, so none of them has to remember to clear or announce.
     *
     * On a **user-id change** (the active user actually moved — not a claim that
     * keeps the same id), this:
     *  1. **dumps the departing user's device-local data first** — awaited,
     *     *before* the new state is emitted — so a reactive loader (e.g.
     *     `ProfileRepository` re-resolving on the auth change) wakes only after
     *     the wipe and can't lose freshly-fetched data to a late clear.
     *  2. emits the new state.
     *  3. announces [AppEvent.UserChanged] for side-effects that don't own
     *     user-scoped storage (telemetry binding, dropping an on-screen message).
     *
     * A claim/link (anon `X` → claimed `X`, same id) is *not* a user change: no
     * dump, no announcement — the guest keeps their progress. The state still
     * emits so the profile re-resolves to the now-claimed account and the
     * `isAnonymous` flip refires the level-keyed sync loops (`SyncTriggers`).
     */
    private suspend fun emitLocked(next: AuthState) {
        val previous = lastEmittedOrNull()
        val previousUserId = (previous as? AuthState.Authenticated)?.userId
        val nextUserId = (next as? AuthState.Authenticated)?.userId
        val userChanged = previousUserId != nextUserId

        if (userChanged && previousUserId != null) {
            logger.i { "User changed $previousUserId → ${nextUserId ?: "none"} — dumping prior user's local data before emit" }
            Catching { userScopedDataReset.clearFor(previousUserId) }
                .logOnFailure { "User-scoped data dump for $previousUserId failed; continuing with transition" }
        }

        state.emit(next)

        if (userChanged) {
            appEventBus.dispatch(AppEvent.UserChanged(previous = previousUserId, current = nextUserId))
        }
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
        // No session to refresh — a fresh email signup pending confirmation has
        // none, and supabase-kt throws (rather than returning null) on refreshing
        // nothing. Resolve to SessionExpired up front instead of letting that throw
        // fall through to Unknown, which strands the verify screen on a silent
        // no-op (AppResumed swallows Unknown).
        if (gateway.currentSession() == null) {
            logger.d { "refreshSession: no current session → SessionExpired" }
            return@withLock RefreshOutcome.SessionExpired
        }
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
        // emitUnauthenticatedLocked → emitLocked dumps the prior user's local
        // data and dispatches UserChanged(prev, null); no explicit dispatch here.
        // Reason.SignedOut so identity self-heal doesn't resurrect this
        // deliberate sign-out as a fresh anonymous guest.
        emitUnauthenticatedLocked(
            cause = null,
            reason = AuthState.Unauthenticated.Reason.SignedOut,
        )
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
            logger.i { "deleteAccount: Success — signing out + clearing user-scoped data" }
            Catching { gateway.signOut() }
                .logOnFailure { "Supabase signOut after delete failed; clearing local state anyway" }
            // emitUnauthenticatedLocked → emitLocked dumps this user's local
            // data and dispatches UserChanged(prev, null). Reason.SignedOut so
            // identity self-heal treats a delete like a sign-out and doesn't
            // mint a fresh guest in its place.
            emitUnauthenticatedLocked(
                cause = null,
                reason = AuthState.Unauthenticated.Reason.SignedOut,
            )
        } else {
            logger.w { "deleteAccount: ${outcome::class.simpleName}" }
        }
        outcome
    }

    override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome {
        // Phase 1 (under the lock): guard, launch the browser, park a pending
        // handle. supabase-kt's linkIdentity(OAuth) only OPENS the browser — the
        // claim isn't done until the cards://login-callback redirect returns and
        // [completeOAuthRedirect] resolves the deferred. Do NOT emit here.
        val deferred = mutex.withLock {
            logger.d { "linkOAuthIdentity: launching browser for $provider" }
            if (gateway.currentSession() == null) {
                logger.w { "linkOAuthIdentity: NotSignedIn (no supabase session)" }
                return LinkIdentityOutcome.NotSignedIn
            }
            val launch = Catching { gateway.linkOAuthIdentity(provider) }
            launch.exceptionOrNull()?.let { e ->
                val outcome = when (e) {
                    is RestException -> mapLinkRestException(e)
                    is HttpRequestException -> LinkIdentityOutcome.NetworkError(e)
                    else ->
                        if ((e.message ?: "").contains("cancel", ignoreCase = true)) {
                            LinkIdentityOutcome.Cancelled
                        } else LinkIdentityOutcome.Unknown(e)
                }
                logger.w(e) { "linkOAuthIdentity($provider): launch failed → ${outcome::class.simpleName}" }
                return outcome
            }
            startPendingOAuthLocked(OAuthFlowKind.Link).result
        }

        // Phase 2 (OUTSIDE the lock — awaiting it under the mutex would deadlock
        // completeOAuthRedirect, which needs the same mutex to resolve us).
        return when (val result = awaitOAuthRedirect(deferred)) {
            OAuthRedirectResult.Success -> {
                logger.i { "linkOAuthIdentity($provider): Success" }
                LinkIdentityOutcome.Success
            }
            OAuthRedirectResult.Cancelled -> {
                logger.i { "linkOAuthIdentity($provider): Cancelled" }
                LinkIdentityOutcome.Cancelled
            }
            is OAuthRedirectResult.Failed -> {
                val outcome = when (val e = result.cause) {
                    is RestException -> mapLinkRestException(e)
                    is HttpRequestException -> LinkIdentityOutcome.NetworkError(e)
                    else -> LinkIdentityOutcome.Unknown(e)
                }
                logger.w(result.cause) { "linkOAuthIdentity($provider): ${outcome::class.simpleName}" }
                outcome
            }
        }
    }

    override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome {
        // Phase 1 (under the lock): launch the browser, park a pending handle. As
        // with link, the session isn't here yet — it arrives via the redirect, so
        // do NOT emit. The starter awaits the deferred outside the lock below.
        val deferred = mutex.withLock {
            logger.d { "signInWithOAuth: launching browser for $provider" }
            val launch = Catching { gateway.signInWithOAuth(provider) }
            launch.exceptionOrNull()?.let { e ->
                val outcome = when (e) {
                    is RestException -> mapOAuthSignInRestException(e)
                    is HttpRequestException -> SignInOutcome.NetworkError(e)
                    else ->
                        if ((e.message ?: "").contains("cancel", ignoreCase = true)) {
                            SignInOutcome.Cancelled
                        } else SignInOutcome.Unknown(e)
                }
                logger.w(e) { "signInWithOAuth($provider): launch failed → ${outcome::class.simpleName}" }
                return outcome
            }
            startPendingOAuthLocked(OAuthFlowKind.SignIn).result
        }

        // Phase 2 (OUTSIDE the lock — see linkOAuthIdentity for why).
        return when (val result = awaitOAuthRedirect(deferred)) {
            OAuthRedirectResult.Success -> {
                logger.i { "signInWithOAuth($provider): Success" }
                SignInOutcome.Success
            }
            OAuthRedirectResult.Cancelled -> {
                logger.i { "signInWithOAuth($provider): Cancelled" }
                SignInOutcome.Cancelled
            }
            is OAuthRedirectResult.Failed -> {
                val outcome = when (val e = result.cause) {
                    is RestException -> mapOAuthSignInRestException(e)
                    is HttpRequestException -> SignInOutcome.NetworkError(e)
                    else -> SignInOutcome.Unknown(e)
                }
                logger.w(result.cause) { "signInWithOAuth($provider): ${outcome::class.simpleName}" }
                outcome
            }
        }
    }

    override fun isOAuthRedirect(url: String): Boolean = gateway.isOAuthRedirect(url)

    /**
     * The browser OAuth round trip landed back on `cards://login-callback`. Finish
     * the pending [signInWithOAuth] / [linkOAuthIdentity] by resolving its deferred
     * — which un-parks the suspended starter so the outcome-driven UI gets its
     * answer. Never throws out of this path: any failure resolves the handle with a
     * mapped failure and leaves auth state untouched.
     *
     * The [SignInOutcome] return is vestigial (the deep-link collector ignores it);
     * the real result flows to the parked starter. We still return a best-effort
     * value for the interface contract.
     */
    override suspend fun completeOAuthRedirect(url: String): SignInOutcome =
        mutex.withLock {
            val pending = pendingOAuth
            if (pending == null) {
                // Stray / duplicate redirect (no starter waiting) — nothing to
                // finish. No-op rather than guess at a session change.
                logger.w { "completeOAuthRedirect: no pending OAuth handle — ignoring stray redirect" }
                return@withLock SignInOutcome.Cancelled
            }
            logger.d { "completeOAuthRedirect: finishing pending ${pending.kind} flow" }
            Catching {
                when (pending.kind) {
                    // Sign-in: the session lives in the URL fragment — parse + import.
                    OAuthFlowKind.SignIn -> gateway.completeOAuthRedirect(url)
                    // Link/claim: the redirect carries NO session, and the browser
                    // flow already attached the identity server-side. Fetching the
                    // user (hydrateCurrentUser) is NOT enough: the cached access
                    // token still carries is_anonymous=true and GET /user doesn't
                    // fold the just-linked identity into the local session, so the
                    // app stays "anonymous" and the Save-your-progress banner
                    // persists (observed on device — the link reported Success while
                    // the session was still the anon user with no email). Force a
                    // session refresh so a fresh JWT + user (now carrying the linked
                    // identity, is_anonymous=false) replaces the stale one — the same
                    // fix the native Apple link path uses. The URL is irrelevant past
                    // routing.
                    OAuthFlowKind.Link -> gateway.refreshSession()
                }
                emitAuthenticatedFromGatewayLocked()
            }.fold(
                onSuccess = {
                    logger.i { "completeOAuthRedirect: ${pending.kind} Success" }
                    resolvePendingOAuthLocked(OAuthRedirectResult.Success)
                    SignInOutcome.Success
                },
                onFailure = { e ->
                    // No tokens in the fragment (sign-in) — the user backed out of
                    // the consent screen, or the provider sent an error redirect.
                    val redirectResult = if (e is IllegalArgumentException) {
                        OAuthRedirectResult.Cancelled
                    } else {
                        OAuthRedirectResult.Failed(e)
                    }
                    logger.w(e) { "completeOAuthRedirect: ${pending.kind} failed → ${redirectResult::class.simpleName}" }
                    resolvePendingOAuthLocked(redirectResult)
                    SignInOutcome.Cancelled
                },
            )
        }

    /**
     * Install a fresh pending OAuth handle, cancelling/replacing any prior one
     * (a new attempt while one is in flight resolves the old as Cancelled so its
     * starter unsuspends). Lock must be held.
     */
    private fun startPendingOAuthLocked(kind: OAuthFlowKind): PendingOAuth {
        pendingOAuth?.let { stale ->
            logger.w { "startPendingOAuth: replacing an in-flight ${stale.kind} attempt — resolving it Cancelled" }
            stale.result.complete(OAuthRedirectResult.Cancelled)
        }
        return PendingOAuth(kind, CompletableDeferred()).also { pendingOAuth = it }
    }

    /** Resolve + clear the current pending handle. Lock must be held. */
    private fun resolvePendingOAuthLocked(result: OAuthRedirectResult) {
        pendingOAuth?.result?.complete(result)
        pendingOAuth = null
    }

    /**
     * Await the redirect's resolution with a generous backstop timeout. The
     * timeout is the ONLY guard against a hang: a user who backs out of the
     * browser (the app foregrounds with no redirect) leaves the deferred unparked
     * until it fires. Clean "backed out" cancellation is a known rough edge we'll
     * refine during device testing — we deliberately don't build foreground-race
     * detection here.
     */
    private suspend fun awaitOAuthRedirect(
        deferred: CompletableDeferred<OAuthRedirectResult>,
    ): OAuthRedirectResult = try {
        withTimeout(OAuthRedirectTimeout) { deferred.await() }
    } catch (e: TimeoutCancellationException) {
        logger.w(e) { "awaitOAuthRedirect: timed out after $OAuthRedirectTimeout — treating as Cancelled" }
        mutex.withLock {
            // Only clear if this is still the handle we were waiting on; a newer
            // attempt may have already replaced it.
            if (pendingOAuth?.result === deferred) pendingOAuth = null
        }
        OAuthRedirectResult.Cancelled
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

        /**
         * Backstop for a browser-OAuth attempt that never gets a redirect back
         * (user abandoned the consent screen / killed the browser tab). Generous
         * because a real consent flow can take a while; on expiry we resolve the
         * starter as Cancelled rather than leave it suspended forever.
         */
        val OAuthRedirectTimeout = 3.minutes
    }
}
