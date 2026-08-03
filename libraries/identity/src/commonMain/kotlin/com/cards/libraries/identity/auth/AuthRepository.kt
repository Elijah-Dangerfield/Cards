package com.dangerfield.cards.libraries.identity.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Owns the device's Supabase user lifecycle + access token.
 *
 * Sessions are never minted implicitly. Construction only hydrates whatever
 * session supabase-kt persisted; new sessions are created explicitly —
 * onboarding drives [createGuestSession] (via `GuestAccountCreator`), the
 * sign-in flows mint claimed ones, and `GuestSessionHealer` recovers a
 * stranded onboarded device. [retry] re-resolves a dormant session but never
 * mints. After init resolves, [current] / [observe] return either
 * [AuthState.Authenticated] or [AuthState.Unauthenticated] (whose
 * [AuthState.Unauthenticated.reason] says why: none/offline, session
 * expired, signed out).
 *
 * **No in-flight sentinel state.** [current] suspends until the answer
 * is real; [observe] emits only resolved values. UI that wants to render
 * a spinner should do so while waiting on its first emission.
 *
 * Errors from auth operations are returned as sealed outcome types
 * rather than thrown, because the UI wants to render specific messages
 * for "invalid credentials" vs "network down" vs "email already
 * registered." Try/catch at every call site was the worse alternative.
 *
 * **Access tokens live elsewhere.** The networking layer reads tokens via
 * `AuthTokenProvider` (in `:libraries:networking`), not through this
 * interface — keeping the network → auth dependency narrow at the type
 * level.
 */
interface AuthRepository {

    /**
     * Suspends until auth resolves to a definitive state. Idempotent —
     * concurrent callers share one in-flight resolve.
     */
    suspend fun current(): AuthState

    /**
     * Reactive stream of auth state changes. First emission lands after
     * the initial resolve completes. Subsequent emissions on sign-in,
     * sign-out, account delete, etc.
     */
    fun observe(): Flow<AuthState>

    /**
     * Re-resolve the persisted session after a previous failure. Used by the
     * connectivity observer (offline → online flip) and explicit retry
     * actions. No-op if already Authenticated. Does **not** create an account —
     * a missing session stays [AuthState.Unauthenticated].
     */
    suspend fun retry(): AuthState

    /**
     * Suspends until the session resolves to [AuthState.Authenticated], then
     * returns it. For callers that *must* have a real session before proceeding
     * (so they never fire a tokenless request that 401s). Never resolves while
     * the user is session-less — pair with a timeout if the caller can't block
     * indefinitely.
     */
    suspend fun awaitAuthenticated(): AuthState.Authenticated =
        observe().first { it is AuthState.Authenticated } as AuthState.Authenticated

    /**
     * Run [block] only if the session is currently [AuthState.Authenticated];
     * otherwise no-op and return null. The guard for fire-and-forget authed
     * syncs: a session-less resolve **skips** the call (an intentional, logged
     * no-op) instead of 401ing. Re-fires when auth later arrives via the
     * level-keyed sync triggers (`SyncTriggers.activeAccount`).
     */
    suspend fun <T> ifAuthenticated(block: suspend (AuthState.Authenticated) -> T): T? =
        (current() as? AuthState.Authenticated)?.let { block(it) }

    /**
     * Create a fresh anonymous (guest) session and emit
     * [AuthState.Authenticated]. The app no longer auto-creates one on launch —
     * onboarding calls this when the user commits to playing as a guest, so a
     * throwaway anon account is never minted for users who sign in instead.
     *
     * Default-throws so test fakes that don't drive guest creation needn't
     * implement it; the production impl overrides.
     */
    suspend fun createGuestSession(): SignInOutcome =
        throw NotImplementedError("createGuestSession not implemented by ${this::class.simpleName}")

    /** Email/password sign-in. Server-issued JWT replaces any current session. */
    suspend fun signInWithEmail(email: String, password: String): SignInOutcome

    /**
     * Email/password sign-up. Supabase sends a verification email; the
     * session is in a "pending email confirmation" state until the user
     * clicks the link AND we call [refreshSession]. Until then,
     * `/v1/me`-protected calls will succeed because the JWT itself is
     * valid — the verification gate is product-level.
     */
    suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome

    /**
     * Pulls the current Supabase session from the server. Used by the
     * "I clicked the verification link" button on the email-verification
     * screen to learn whether the user's email is now confirmed.
     */
    suspend fun refreshSession(): RefreshOutcome

    /** Resend the verification email for the current pending sign-up. */
    suspend fun resendVerificationEmail(email: String): ResendOutcome

    /**
     * Trigger Supabase's password-reset email. The user clicks the link in
     * their inbox, which lands them on the recovery deep-link target — that
     * screen lives in a follow-up once the redirect URL is configured.
     *
     * Always returns [SendResetOutcome.Sent] for unknown emails too: Supabase
     * doesn't differentiate at the API layer so the UI shouldn't either
     * (account-enumeration mitigation).
     */
    suspend fun sendPasswordResetEmail(email: String): SendResetOutcome

    /**
     * Declare the device's persisted session unrecoverable: no token survived
     * (storage lost it) and [retry] couldn't revive one, but local state says a
     * real account exists. Tears down like a server rejection — emits
     * [AuthState.Unauthenticated] with [AuthState.Unauthenticated.Reason.SessionExpired]
     * so the app routes to the recovery screen instead of anything minting a
     * fresh guest over the stranded account (AUTH-19). [wasAnonymous] routes the
     * guest copy (unrecoverable, start fresh) vs claimed copy (sign in again).
     *
     * Default no-op so test fakes that don't drive recovery needn't implement
     * it; the production impl overrides.
     */
    suspend fun markSessionUnrecoverable(wasAnonymous: Boolean) {}

    /**
     * Tear down the current Supabase session. The next [current] call
     * will trigger a fresh anonymous sign-in.
     */
    suspend fun signOut()

    /**
     * Permanently delete the current account. Calls the server's
     * `DELETE /v1/me` (which in turn invokes Supabase's Admin API to
     * remove `auth.users` plus drops the local profile row) and then
     * tears down the local Supabase session.
     */
    suspend fun deleteAccount(): DeleteAccountOutcome

    /**
     * Attach an Apple/Google identity to the current (typically anonymous)
     * Supabase user. Preserves chips, XP, and history.
     */
    suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome

    /**
     * Switch sessions to an existing OAuth account. The current local
     * session is replaced; any guest progress tied to the previous
     * session is orphaned by design.
     *
     * Note: for the browser OAuth flow this only *launches* the system browser.
     * The session is established later, when the provider redirects back to
     * `cards://login-callback` and the app forwards that URL to
     * [completeOAuthRedirect]. A `SignInOutcome.Success` here means "browser
     * opened", not "signed in".
     */
    suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome

    /**
     * Whether [url] is the browser-OAuth redirect deep link (`cards://login-callback`).
     * The app's deep-link collector uses this to send auth callbacks to
     * [completeOAuthRedirect] and route everything else through the nav graph.
     *
     * Defaults to false so test fakes that don't drive OAuth needn't implement
     * it; the production impl overrides.
     */
    fun isOAuthRedirect(url: String): Boolean = false

    /**
     * Finish a browser OAuth sign-in from the redirect deep link. [url] is the
     * full `cards://login-callback#access_token=…` callback. Imports the session
     * supabase-kt parsed out of the URL and emits the resulting
     * [AuthState.Authenticated] (replacing any prior session). Returns the
     * outcome so the caller can surface a failure (cancelled / network).
     *
     * Default-throws so test fakes that don't drive OAuth needn't implement it;
     * the production impl overrides.
     */
    suspend fun completeOAuthRedirect(url: String): SignInOutcome =
        throw NotImplementedError("completeOAuthRedirect not implemented by ${this::class.simpleName}")

    /**
     * Native Sign in with Apple — exchange the [credential] (id token + raw
     * nonce captured by [AppleSignInCoordinator]) for a Supabase session.
     * Replaces the current session, like [signInWithOAuth].
     *
     * Default-throws so the test fakes that don't exercise native sign-in
     * needn't implement it; the production impl overrides.
     */
    suspend fun signInWithApple(credential: AppleSignInCredential): SignInOutcome =
        throw NotImplementedError("signInWithApple not implemented by ${this::class.simpleName}")

    /**
     * Attach a native Apple identity to the current (typically anonymous) user,
     * preserving chips / XP / history. Default-throws (see [signInWithApple]).
     */
    suspend fun linkAppleIdentity(credential: AppleSignInCredential): LinkIdentityOutcome =
        throw NotImplementedError("linkAppleIdentity not implemented by ${this::class.simpleName}")

    /**
     * Native Google id-token sign-in — exchange a Google id token (+ optional
     * nonce) for a session. Wired ahead of a Google token source (Credential
     * Manager / GIDSignIn) landing. Default-throws (see [signInWithApple]).
     */
    suspend fun signInWithGoogleIdToken(idToken: String, nonce: String? = null): SignInOutcome =
        throw NotImplementedError("signInWithGoogleIdToken not implemented by ${this::class.simpleName}")

    /**
     * Attach an email/password to the current anonymous Supabase user.
     * Triggers a verification email; the user is anonymous until they
     * click the link (see [refreshSession]).
     */
    suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome
}

/**
 * Resolved auth state. No in-flight sentinel — consumers suspend on
 * [AuthRepository.current] / observe the first [AuthRepository.observe]
 * emission instead.
 *
 * `Authenticated` covers both anonymous and claimed (email/OAuth) sessions
 * — the `isAnonymous` flag distinguishes when it matters (e.g. the
 * "claim your account" prompt).
 *
 * `Unauthenticated` is the fallback case: no Supabase session, no working
 * token, the network layer can't attach a bearer. Profile features fall
 * back to client-only state (`Profile.Fallback`). When connectivity
 * returns, [AuthRepository.retry] re-attempts.
 */
sealed interface AuthState {
    data class Authenticated(
        /** Supabase `auth.users.id`. The single source of user identity. */
        val userId: String,
        val isAnonymous: Boolean,
        val email: String?,
    ) : AuthState

    data class Unauthenticated(
        /** Why the last resolve failed. Null when nothing's been attempted. */
        val cause: Throwable? = null,
        /** What kind of unauthenticated state this is — drives app-level routing. */
        val reason: Reason = Reason.None,
        /**
         * Whether the session we lost was anonymous (guest). Only meaningful for
         * [Reason.SessionExpired]; lets the app route a guest (unrecoverable —
         * start fresh) differently from a claimed account (sign in again).
         */
        val wasAnonymous: Boolean = false,
    ) : AuthState {

        /**
         * Why we're unauthenticated.
         *
         * - [None] — no session / not-yet-resolved / offline. No forced routing;
         *   the auth gate handles navigation as usual. This is also the reason a
         *   never-signed-in-but-onboarded guest (the stranding case) carries, so
         *   identity self-heal mints a session for it.
         * - [SessionExpired] — the session is genuinely dead: either the auth
         *   server **rejected** our token and a refresh failed, or the client
         *   declared it unrecoverable ([AuthRepository.markSessionUnrecoverable] —
         *   storage lost the token while a cached account exists). The app boots
         *   the user to re-authenticate (claimed) or start fresh (guest). Sticky
         *   within the run: a re-resolve that still finds no session keeps this
         *   reason instead of decaying to [None], so identity self-heal never
         *   mints over a dead-but-declared session.
         * - [SignedOut] — a **deliberate** sign-out / account delete this run. The
         *   distinction from [None] is load-bearing for identity self-heal: it must
         *   NOT resurrect a user who just chose to sign out as a fresh anonymous
         *   guest. (After a relaunch this collapses back to [None]; the
         *   `hasUserOnboarded` guard — cleared on sign-out — covers that case.)
         * - [Unreachable] — the session couldn't be **verified** because the
         *   backend was unreachable (captive-portal / dead-but-connected WiFi): the
         *   resolve exhausted its attempts stuck on supabase's *transient* refresh
         *   states, which — unlike a server `NotAuthenticated` — is NOT a
         *   confirmed-dead session. The session may well still be alive. Identity
         *   self-heal must NOT tear it down or mint over it (AUTH-30); it keeps the
         *   cached identity, the app shows the honest offline surface, and the next
         *   connectivity/foreground edge re-resolves. Deliberately **not sticky**
         *   (unlike [SessionExpired]): a later definitive `NotAuthenticated` must
         *   still be able to settle [None] so a genuinely-rejected session can tear
         *   down on a subsequent edge.
         */
        enum class Reason { None, SessionExpired, SignedOut, Unreachable }
    }
}
