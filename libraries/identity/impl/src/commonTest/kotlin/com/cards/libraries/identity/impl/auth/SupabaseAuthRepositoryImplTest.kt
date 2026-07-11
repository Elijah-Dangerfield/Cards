package com.dangerfield.cards.libraries.identity.impl.auth

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventBus
import com.dangerfield.cards.libraries.cards.UserScopedDataReset
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.RefreshOutcome
import com.dangerfield.cards.libraries.identity.impl.MeDto
import com.dangerfield.cards.libraries.identity.impl.PatchMeRequest
import com.dangerfield.cards.libraries.identity.impl.ProfileApi
import com.dangerfield.cards.libraries.identity.impl.AvatarPackResponseDto
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [SupabaseAuthRepositoryImpl]'s resolve loop + token contract.
 *
 * The interesting edges all flow through the [SupabaseAuthGateway] seam:
 *  - cold-boot resolve: `Initializing → … → Authenticated`,
 *  - no-session resolve: `NotAuthenticated → Unauthenticated` (NO auto-anon),
 *  - explicit guest creation: `createGuestSession → signInAnonymously →
 *    Authenticated`,
 *  - exhausted resolve attempts (gateway stays Transient forever),
 *  - `retry()` is a no-op when already Authenticated,
 *  - `signOut()` flips state, dumps the prior user's local data, and
 *    dispatches `AppEvent.UserChanged(prev, null)`,
 *  - an account switch dumps the departing user's data *before* emitting the
 *    new session, and announces `UserChanged(prev, next)`,
 *  - `current()` suspends pre-resolve, then unsuspends after.
 *
 * Tests deliberately *don't* construct a real `SupabaseClient` — the
 * gateway interface is the unit-test seam. Tests for the sign-in /
 * sign-up / link error-mapping outcomes belong in a separate file (they
 * exercise the supabase-kt exception classes, which are not synthesizable
 * cheaply from commonTest yet).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupabaseAuthRepositoryImplTest : CoroutineTest() {

    // ---------- resolve loop ----------

    @Test
    fun resolve_authenticatedOnFirstPoll_emitsAuthenticatedImmediately() = runUnitTest {
        // Warm boot path: supabase-kt already has a persisted session.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = claimedSession(),
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        val state = assertIs<AuthState.Authenticated>(repo.current())
        assertEquals("user-1", state.userId)
        assertEquals(false, state.isAnonymous)
        assertEquals("a@b.com", state.email)
        assertEquals(0, gateway.signInAnonymouslyCalls, "no anon sign-in when already authenticated")
    }

    // ---------- session rejection (server rejected our token) ----------

    @Test
    fun sessionRejected_tearsDown_toSessionExpired_carryingAnonymity() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = anonymousSession(),
        )
        val bus = FakeSessionRejectionBus()
        val repo = build(gateway = gateway, sessionRejectionBus = bus)
        advanceUntilIdle()
        assertIs<AuthState.Authenticated>(repo.current())

        // The token layer reports the auth server rejected our (guest) session.
        bus.signalRejected(wasAnonymous = true)
        advanceUntilIdle()

        val state = assertIs<AuthState.Unauthenticated>(repo.current())
        assertEquals(AuthState.Unauthenticated.Reason.SessionExpired, state.reason)
        assertTrue(state.wasAnonymous, "the lost session's anonymity must survive for routing")
        assertEquals(1, gateway.signOutCalls, "the dead supabase session is torn down")
    }

    @Test
    fun sessionRejected_isIdempotent_acrossABurst() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = claimedSession(),
        )
        val bus = FakeSessionRejectionBus()
        val repo = build(gateway = gateway, sessionRejectionBus = bus)
        advanceUntilIdle()

        // A storm of rejected requests all signal; the teardown happens once.
        bus.signalRejected(wasAnonymous = false)
        bus.signalRejected(wasAnonymous = false)
        advanceUntilIdle()

        assertIs<AuthState.Unauthenticated>(repo.current())
        assertEquals(1, gateway.signOutCalls, "a burst collapses to a single teardown")
    }

    @Test
    fun resolve_notAuthenticated_emitsUnauthenticated_withoutCreatingAnAccount() = runUnitTest {
        // Cold-boot fresh-install path: no session. We no longer auto-create
        // one — the app stays Unauthenticated until onboarding mints an
        // account explicitly. This is what kills the orphan-anon problem.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        val state = assertIs<AuthState.Unauthenticated>(repo.current())
        assertNull(state.cause, "a clean no-session resolve carries no error")
        assertEquals(0, gateway.signInAnonymouslyCalls, "no anonymous sign-in on launch")
    }

    @Test
    fun createGuestSession_signsInAnonymously_andEmitsAuthenticated() = runUnitTest {
        // Explicit guest creation (onboarding finish): anon sign-in fires here,
        // not on launch.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
            onSignInAnonymously = { advanceToAuthenticated(anonymousSession()) },
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()
        assertIs<AuthState.Unauthenticated>(repo.current())

        val outcome = repo.createGuestSession()
        assertIs<com.dangerfield.cards.libraries.identity.auth.SignInOutcome.Success>(outcome)
        val state = assertIs<AuthState.Authenticated>(repo.current())
        assertEquals(true, state.isAnonymous)
        assertNull(state.email)
        assertEquals(1, gateway.signInAnonymouslyCalls)
    }

    @Test
    fun createGuestSession_whenAnonSignInThrows_returnsFailureOutcome_andStaysUnauthenticated() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
            onSignInAnonymously = { throw RuntimeException("offline") },
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        val outcome = repo.createGuestSession()
        assertIs<com.dangerfield.cards.libraries.identity.auth.SignInOutcome.Unknown>(outcome)
        assertIs<AuthState.Unauthenticated>(repo.current())
    }

    @Test
    fun resolve_initializing_loopsUntilSettled() = runUnitTest {
        // Mid-init path: status reports Initializing once, then Authenticated.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Initializing,
            session = claimedSession(),
            statusSequence = listOf(
                AuthGatewayStatus.Initializing,
                AuthGatewayStatus.Authenticated,
            ),
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        assertIs<AuthState.Authenticated>(repo.current())
        assertTrue(gateway.statusReads >= 2, "resolve must re-poll after a Transient status")
    }

    @Test
    fun resolve_alwaysTransient_exhaustsAttempts_andEmitsUnauthenticatedNoCause() = runUnitTest {
        // Pathological path: gateway never settles. After MaxResolveAttempts
        // (5) iterations we emit Unauthenticated(cause=null) — distinguishable
        // from a real error because cause is null.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Initializing,
            session = null,
            // Every poll reports Initializing forever.
            statusSequence = List(10) { AuthGatewayStatus.Initializing },
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        val state = assertIs<AuthState.Unauthenticated>(repo.current())
        assertNull(state.cause, "exhausted resolve has no exception attached")
    }

    @Test
    fun observe_emitsEachStateTransition() = runUnitTest {
        // After the initial resolve, every subsequent emit lands on the
        // shared flow. We pin the no-cause Unauthenticated → signOut path.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = claimedSession(),
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        repo.observe().test {
            assertIs<AuthState.Authenticated>(awaitItem())
            repo.signOut()
            assertIs<AuthState.Unauthenticated>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- current() suspension ----------

    @Test
    fun current_suspendsUntilResolveCompletes() = runUnitTest {
        // The whole reason for the resolve loop: callers (including the
        // network client's bearer plugin) suspend until auth lands.
        val initGate = CompletableDeferred<Unit>()
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = claimedSession(),
            onAwaitInitialization = { initGate.await() },
        )
        val repo = build(gateway = gateway)

        val pending = async { repo.current() }
        // Yield the dispatcher to let the resolve start.
        advanceUntilIdle()
        assertTrue(pending.isActive, "current() must still be suspended until session hydration completes")

        initGate.complete(Unit)
        advanceUntilIdle()
        assertIs<AuthState.Authenticated>(pending.await())
    }

    // Token methods (accessToken / refreshAccessToken) live on
    // [GatewayAuthTokenProvider] now — see [GatewayAuthTokenProviderTest].

    // ---------- retry ----------

    @Test
    fun retry_isNoOp_whenAlreadyAuthenticated() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = claimedSession(),
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        val readsBefore = gateway.statusReads
        val result = repo.retry()
        assertIs<AuthState.Authenticated>(result)
        assertEquals(readsBefore, gateway.statusReads, "retry must not re-poll when authed")
    }

    @Test
    fun retry_reRunsResolve_afterUnauthenticated() = runUnitTest {
        // First resolve finds no session → Unauthenticated. Later a session
        // appears (e.g. it hydrated after a transient failure, or a sign-in
        // landed out of band); retry() re-resolves and lands authed.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()
        assertIs<AuthState.Unauthenticated>(repo.current())

        gateway.replaceSession(claimedSession())
        gateway.setStatus(AuthGatewayStatus.Authenticated)
        val result = repo.retry()
        assertIs<AuthState.Authenticated>(result)
    }

    // ---------- signOut ----------

    @Test
    fun signOut_clearsState_dumpsPriorUser_andAnnouncesUserChange() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = claimedSession(),
        )
        val events = RecordingEventBus()
        val reset = RecordingUserScopedDataReset()
        val repo = build(gateway = gateway, appEventBus = events, userScopedDataReset = reset)
        advanceUntilIdle()

        repo.signOut()
        assertIs<AuthState.Unauthenticated>(repo.current())
        assertEquals(1, gateway.signOutCalls)
        // The departing user's device-local data is dumped exactly once.
        assertEquals(listOf("user-1"), reset.clearedFor)
        // Boot announced (null → user-1); sign-out announced (user-1 → null).
        assertEquals(
            listOf<AppEvent>(
                AppEvent.UserChanged(previous = null, current = "user-1"),
                AppEvent.UserChanged(previous = "user-1", current = null),
            ),
            events.dispatched,
        )
    }

    @Test
    fun signOut_emitsSignedOutReason_soHealerDoesNotResurrect() = runUnitTest {
        // The reason distinguishes a deliberate sign-out from a never-signed-in
        // stranded guest. Without it, identity self-heal would mint a fresh
        // anonymous guest right after the user chose to sign out.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = claimedSession(),
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        repo.signOut()

        val state = assertIs<AuthState.Unauthenticated>(repo.current())
        assertEquals(AuthState.Unauthenticated.Reason.SignedOut, state.reason)
    }

    @Test
    fun accountSwitch_dumpsDepartingUser_beforeEmit_andAnnouncesUserChange() = runUnitTest {
        // Start signed in as a guest, then sign into a *different* existing
        // account via the browser flow. signInWithOAuth only opens the browser;
        // the switch lands when completeOAuthRedirect imports the new session.
        // The guest's local data must be dumped before the new session is
        // announced, and the switch announced as UserChanged.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = anonymousSession(userId = "guest-1"),
            onCompleteOAuthRedirect = {
                advanceToAuthenticated(claimedSession(userId = "real-2", email = "real@b.com"))
            },
        )
        val events = RecordingEventBus()
        val reset = RecordingUserScopedDataReset()
        val repo = build(gateway = gateway, appEventBus = events, userScopedDataReset = reset)
        advanceUntilIdle()

        // Start the sign-in (parks, awaiting the redirect), then drive the redirect.
        // runCurrent (not advanceUntilIdle) so the 3-min await timeout doesn't fire.
        val signIn = async { repo.signInWithOAuth(OAuthProvider.Google) }
        runCurrent()
        repo.completeOAuthRedirect("cards://login-callback#access_token=t")
        assertIs<com.dangerfield.cards.libraries.identity.auth.SignInOutcome.Success>(signIn.await())

        val state = assertIs<AuthState.Authenticated>(repo.current())
        assertEquals("real-2", state.userId)
        assertEquals(false, state.isAnonymous)
        // The guest's data — and only the guest's — was dumped.
        assertEquals(listOf("guest-1"), reset.clearedFor)
        // The claim case is excluded: a switch to a *different* id announces.
        assertEquals(
            AppEvent.UserChanged(previous = "guest-1", current = "real-2"),
            events.dispatched.last(),
        )
    }

    @Test
    fun accountClaim_sameUserId_doesNotDump_andAnnouncesNothing() = runUnitTest {
        // Linking/claiming keeps the same user id (anon guest-1 becomes a
        // claimed account but stays guest-1). The link redirect carries no
        // session, so completeOAuthRedirect refreshes the session (a stale-token
        // refresh; hydrate alone leaves is_anonymous=true — see the device
        // regression below). The guest's progress is theirs: no dump, no
        // UserChanged, no other announcement — the new AuthState emission's
        // isAnonymous flip is itself what refires the level-keyed sync loops.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = anonymousSession(userId = "guest-1"),
            onRefreshSession = {
                // Same id, now non-anonymous + email — models the fresh JWT + user
                // a session refresh mints after the identity is linked.
                advanceToAuthenticated(claimedSession(userId = "guest-1", email = "claimed@b.com"))
            },
        )
        val events = RecordingEventBus()
        val reset = RecordingUserScopedDataReset()
        val repo = build(gateway = gateway, appEventBus = events, userScopedDataReset = reset)
        advanceUntilIdle()
        val dispatchedAtBoot = events.dispatched.size

        val link = async { repo.linkOAuthIdentity(OAuthProvider.Google) }
        runCurrent()
        repo.completeOAuthRedirect("cards://login-callback")
        assertIs<com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome.Success>(link.await())

        val state = assertIs<AuthState.Authenticated>(repo.current())
        assertEquals("guest-1", state.userId)
        assertEquals(false, state.isAnonymous, "the claim still flips anon → claimed")
        assertEquals(1, gateway.refreshSessionCalls, "link redirect refreshes the session, never parses a session from the URL")
        assertEquals(0, gateway.hydrateCurrentUserCalls, "link redirect must NOT rely on hydrate-only (leaves is_anonymous=true on device)")
        assertEquals(0, gateway.completeOAuthRedirectCalls, "a link redirect must NOT import a session from the URL")
        assertTrue(reset.clearedFor.isEmpty(), "same-user claim must not dump the guest's data")
        val afterClaim = events.dispatched.drop(dispatchedAtBoot)
        assertEquals(
            emptyList<AppEvent>(),
            afterClaim,
            "same-user claim announces nothing — the AuthState emission carries the flip",
        )
    }

    @Test
    fun resolve_authenticatedButSessionNotHydrated_fetchesUserAndResolves_neverThrows() = runUnitTest {
        // Regression: a tokens-only session (status Authenticated but
        // currentSession() == null because the user object isn't fetched yet —
        // an OAuth import or a storage load) must hydrate + recover, NOT crash.
        // The old code did `error("Authenticated status but no session")`, which
        // surfaced on device as repeated red stack traces during sign-in/boot.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = null,
            onHydrateCurrentUser = {
                // Fetching the user populates the session — what supabase's
                // retrieveUserForCurrentSession(updateSession = true) does.
                advanceToAuthenticated(claimedSession(userId = "u-1"))
            },
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        val state = assertIs<AuthState.Authenticated>(repo.current())
        assertEquals("u-1", state.userId)
        assertTrue(gateway.hydrateCurrentUserCalls >= 1, "resolve must hydrate a tokens-only session")
    }

    // ---------- browser OAuth: suspend-until-redirect ----------

    @Test
    fun linkOAuthIdentity_suspendsUntilRedirect_doesNotReturnSuccessWhileAnonymous() = runUnitTest {
        // Regression for the broken flow: linkIdentity(OAuth) only OPENS the
        // browser, so the repo must NOT report Success (and must not stay
        // anonymous) until the cards://login-callback redirect actually lands.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = anonymousSession(userId = "guest-1"),
            onRefreshSession = {
                advanceToAuthenticated(claimedSession(userId = "guest-1", email = "claimed@b.com"))
            },
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        val link = async { repo.linkOAuthIdentity(OAuthProvider.Google) }
        runCurrent()

        // Browser opened, but the redirect hasn't returned: still suspended, and
        // the user is still the anonymous guest. (The old code emitted here and
        // returned Success → "Save your progress" banner persisted.)
        assertTrue(link.isActive, "linkOAuthIdentity must await the redirect, not resolve on browser open")
        assertEquals(1, gateway.linkOAuthIdentityCalls, "the browser was launched exactly once")
        assertTrue(
            assertIs<AuthState.Authenticated>(repo.current()).isAnonymous,
            "still anonymous until the redirect resolves the link",
        )

        // The redirect lands → the link resolves Success, now non-anonymous.
        repo.completeOAuthRedirect("cards://login-callback")
        assertIs<com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome.Success>(link.await())
        assertEquals(false, assertIs<AuthState.Authenticated>(repo.current()).isAnonymous)
    }

    @Test
    fun linkOAuthIdentity_redirect_refreshesSession_evenWhenHydrateAloneLeavesAnonymous() =
        runUnitTest {
            // Device regression: after the Google link redirect the app stayed
            // anonymous (Save-your-progress banner persisted, hasEmail=false,
            // same user id) even though the link reported Success. Root cause:
            // the Link redirect path hydrated the user (GET /user) instead of
            // refreshing the session — and on device GET /user with the still-anon
            // token does NOT fold in the just-linked identity, so is_anonymous
            // stayed true. This test models that exact split: hydrate does NOT
            // claim, only a session refresh does.
            val gateway = FakeSupabaseAuthGateway(
                initialStatus = AuthGatewayStatus.Authenticated,
                session = anonymousSession(userId = "guest-1"),
                onHydrateCurrentUser = {
                    // Hydrate-only: refetches the user but the session is still the
                    // anon one — exactly what the device showed.
                    advanceToAuthenticated(anonymousSession(userId = "guest-1"))
                },
                onRefreshSession = {
                    // The fresh JWT + user the refresh mints carries the linked
                    // identity → claimed, with the real email surfaced.
                    advanceToAuthenticated(claimedSession(userId = "guest-1", email = "claimed@b.com"))
                },
            )
            val repo = build(gateway = gateway)
            advanceUntilIdle()

            val link = async { repo.linkOAuthIdentity(OAuthProvider.Google) }
            runCurrent()
            repo.completeOAuthRedirect("cards://login-callback")
            assertIs<com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome.Success>(
                link.await(),
            )

            val state = assertIs<AuthState.Authenticated>(repo.current())
            assertEquals("guest-1", state.userId, "same id — it's a claim, not a switch")
            assertEquals(false, state.isAnonymous, "the link must flip the user off anonymous")
            assertEquals("claimed@b.com", state.email, "the claimed account's email must surface")
            assertEquals(1, gateway.refreshSessionCalls, "the link redirect refreshes the session")
            assertEquals(0, gateway.hydrateCurrentUserCalls, "hydrate-only is the broken path — must not be used")
        }

    @Test
    fun signInWithOAuth_suspendsUntilRedirect_thenParsesImportsAndReturnsSuccess() = runUnitTest {
        // Happy sign-in: signInWithOAuth parks awaiting the redirect;
        // completeOAuthRedirect parses+imports the session and emits Authenticated.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
            onCompleteOAuthRedirect = {
                advanceToAuthenticated(claimedSession(userId = "oauth-1", email = "g@b.com"))
            },
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        val signIn = async { repo.signInWithOAuth(OAuthProvider.Google) }
        runCurrent()
        assertTrue(signIn.isActive, "signInWithOAuth must await the redirect, not resolve on browser open")
        assertEquals(1, gateway.signInWithOAuthCalls)

        repo.completeOAuthRedirect("cards://login-callback#access_token=t&refresh_token=r")

        assertIs<com.dangerfield.cards.libraries.identity.auth.SignInOutcome.Success>(signIn.await())
        assertEquals(1, gateway.completeOAuthRedirectCalls, "sign-in imports the session from the URL")
        assertEquals(0, gateway.hydrateCurrentUserCalls, "sign-in does not use the link refresh path")
        val state = assertIs<AuthState.Authenticated>(repo.current())
        assertEquals("oauth-1", state.userId)
        assertEquals(false, state.isAnonymous)
    }

    @Test
    fun completeOAuthRedirect_failure_resolvesSuspendedCall_andDoesNotThrow() = runUnitTest {
        // A redirect that fails to import (e.g. no tokens — user backed out)
        // must resolve the suspended sign-in with a failure outcome, never throw,
        // and leave auth state untouched.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
            onCompleteOAuthRedirect = { throw IllegalArgumentException("No access token found") },
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        val signIn = async { repo.signInWithOAuth(OAuthProvider.Google) }
        runCurrent()

        // Does not throw out of the redirect path.
        repo.completeOAuthRedirect("cards://login-callback#error=access_denied")

        assertIs<com.dangerfield.cards.libraries.identity.auth.SignInOutcome.Cancelled>(signIn.await())
        assertIs<AuthState.Unauthenticated>(repo.current())
    }

    @Test
    fun completeOAuthRedirect_withNoPendingHandle_isNoOp() = runUnitTest {
        // A stray / duplicate redirect with nothing waiting on it must not touch
        // auth state or throw.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        repo.completeOAuthRedirect("cards://login-callback#access_token=t")

        assertIs<AuthState.Unauthenticated>(repo.current())
        assertEquals(0, gateway.completeOAuthRedirectCalls, "no pending handle → the gateway is never touched")
    }

    @Test
    fun isOAuthRedirect_matchesCallbackUrl_only() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        assertTrue(repo.isOAuthRedirect("cards://login-callback#access_token=t"))
        assertEquals(false, repo.isOAuthRedirect("cards://profile"))
    }

    // ---------- refreshSession ----------

    @Test
    fun refreshSession_anonymousUser_returnsStillPending() = runUnitTest {
        // Regression: VerifyEmailScreen's AppResumed handler calls
        // refreshSession() the moment it mounts. If we report EmailConfirmed
        // for any anonymous user (the gateway used to do that via
        // `isAnon || emailConfirmedAt != null`), the user is yanked to Home
        // before they can read the "tap the link in your email" copy.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = anonymousSession(),
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        val outcome = repo.refreshSession()
        assertIs<RefreshOutcome.StillPending>(outcome)
    }

    @Test
    fun refreshSession_emailConfirmedSession_returnsEmailConfirmed() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = claimedSession(),
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        val outcome = repo.refreshSession()
        assertIs<RefreshOutcome.EmailConfirmed>(outcome)
    }

    // ---------- deleteAccount ----------

    @Test
    fun deleteAccount_noSession_returnsNotSignedIn() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
        )
        val repo = build(gateway = gateway)
        advanceUntilIdle()

        val outcome = repo.deleteAccount()
        assertIs<com.dangerfield.cards.libraries.identity.auth.DeleteAccountOutcome.NotSignedIn>(outcome)
    }

    @Test
    fun signOut_proceedsEvenIfGatewayThrows() = runUnitTest {
        // Server-side sign-out can fail (offline, expired token, etc.).
        // The local state still flips to Unauthenticated — leaving the
        // user "stuck signed in" client-side would be worse.
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = claimedSession(),
            onSignOut = { throw RuntimeException("network") },
        )
        val events = RecordingEventBus()
        val reset = RecordingUserScopedDataReset()
        val repo = build(gateway = gateway, appEventBus = events, userScopedDataReset = reset)
        advanceUntilIdle()

        repo.signOut()
        assertIs<AuthState.Unauthenticated>(repo.current())
        // Even on a gateway failure the local dump + announcement still run.
        assertEquals(listOf("user-1"), reset.clearedFor)
        assertEquals(
            AppEvent.UserChanged(previous = "user-1", current = null),
            events.dispatched.last(),
        )
    }

    // ---------- scaffolding ----------

    private fun build(
        gateway: FakeSupabaseAuthGateway,
        appEventBus: AppEventBus = NoOpEventBus,
        userScopedDataReset: UserScopedDataReset = NoOpUserScopedDataReset,
        sessionRejectionBus: com.dangerfield.cards.libraries.networking.SessionRejectionBus =
            FakeSessionRejectionBus(),
    ): SupabaseAuthRepositoryImpl = SupabaseAuthRepositoryImpl(
        gateway = gateway,
        profileApi = UnusedProfileApi,
        appEventBus = appEventBus,
        userScopedDataReset = userScopedDataReset,
        tokenInvalidator = NoOpTokenInvalidator,
        sessionRejectionBus = sessionRejectionBus,
        appScope = AppCoroutineScope(dispatchers),
    )

    private object NoOpUserScopedDataReset : UserScopedDataReset {
        override suspend fun clearFor(previousUserId: String) = Unit
    }

    private class RecordingUserScopedDataReset : UserScopedDataReset {
        val clearedFor: MutableList<String> = mutableListOf()
        override suspend fun clearFor(previousUserId: String) {
            clearedFor += previousUserId
        }
    }

    private object NoOpTokenInvalidator :
        com.dangerfield.cards.libraries.networking.AuthTokenInvalidator {
        override fun invalidate() = Unit
    }

    private fun anonymousSession(
        userId: String = "anon-user",
        accessToken: String = "tok-anon",
    ): GatewaySession = GatewaySession(
        userId = userId,
        email = null,
        accessToken = accessToken,
        isAnonymous = true,
        isEmailConfirmed = false,
    )

    private fun claimedSession(
        userId: String = "user-1",
        email: String = "a@b.com",
        accessToken: String = "tok-claimed",
    ): GatewaySession = GatewaySession(
        userId = userId,
        email = email,
        accessToken = accessToken,
        isAnonymous = false,
        isEmailConfirmed = true,
    )

    private object NoOpEventBus : AppEventBus {
        override fun dispatch(event: AppEvent) = Unit
        override fun eventStream(): Flow<AppEvent> = emptyFlow()
        override fun liveEventStream(): Flow<AppEvent> = emptyFlow()
    }

    private class RecordingEventBus : AppEventBus {
        val dispatched: MutableList<AppEvent> = mutableListOf()
        override fun dispatch(event: AppEvent) {
            dispatched += event
        }
        override fun eventStream(): Flow<AppEvent> = emptyFlow()
        override fun liveEventStream(): Flow<AppEvent> = emptyFlow()
    }

    private object UnusedProfileApi : ProfileApi {
        override suspend fun me(): MeDto = error("ProfileApi not used by these tests")
        override suspend fun patchMe(request: PatchMeRequest): MeDto =
            error("ProfileApi not used by these tests")
        override suspend fun avatars(): AvatarPackResponseDto =
            error("ProfileApi not used by these tests")
        override suspend fun deleteMe(): HttpResponse =
            error("ProfileApi not used by these tests")
    }
}

/**
 * In-memory [SupabaseAuthGateway] for unit tests. Tracks call counts +
 * lets the test drive every method outcome via small callbacks. Default
 * mutations (`onSignInAnonymously`, `onSignOut`, `onRefreshSession`)
 * succeed silently and leave session state untouched — overrides set
 * `currentStatus` / `currentSession` as needed.
 *
 * [statusSequence] lets a test simulate a status that flips across
 * resolve passes (e.g. Initializing → Authenticated). If null, every
 * read returns [initialStatus] (which mutations may overwrite).
 */
internal class FakeSupabaseAuthGateway(
    initialStatus: AuthGatewayStatus,
    session: GatewaySession?,
    statusSequence: List<AuthGatewayStatus>? = null,
    var onAwaitInitialization: suspend FakeSupabaseAuthGateway.() -> Unit = {},
    var onSignInAnonymously: suspend FakeSupabaseAuthGateway.() -> Unit = {},
    var onSignOut: suspend FakeSupabaseAuthGateway.() -> Unit = {},
    var onRefreshSession: suspend FakeSupabaseAuthGateway.() -> Unit = {},
    var onSignInWithOAuth: suspend FakeSupabaseAuthGateway.(OAuthProvider) -> Unit = {},
    var onLinkOAuthIdentity: suspend FakeSupabaseAuthGateway.(OAuthProvider) -> Unit = {},
    var onCompleteOAuthRedirect: suspend FakeSupabaseAuthGateway.(String) -> Unit = {
        error("completeOAuthRedirect not stubbed for this test")
    },
    var onHydrateCurrentUser: suspend FakeSupabaseAuthGateway.() -> Unit = {
        error("hydrateCurrentUser not stubbed for this test")
    },
) : SupabaseAuthGateway {

    private var status: AuthGatewayStatus = initialStatus
    private var session: GatewaySession? = session
    private val statusIterator: Iterator<AuthGatewayStatus>? = statusSequence?.iterator()

    var statusReads: Int = 0
        private set
    var signInAnonymouslyCalls: Int = 0
        private set
    var refreshSessionCalls: Int = 0
        private set
    var signOutCalls: Int = 0
        private set
    var signInWithOAuthCalls: Int = 0
        private set
    var linkOAuthIdentityCalls: Int = 0
        private set
    var completeOAuthRedirectCalls: Int = 0
        private set
    var hydrateCurrentUserCalls: Int = 0
        private set

    /**
     * Flip the gateway to Authenticated with the given session. Use from
     * inside an `onSignInAnonymously` block to model "supabase-kt's status
     * moves to Authenticated after the anon sign-in resolves".
     */
    fun advanceToAuthenticated(session: GatewaySession) {
        status = AuthGatewayStatus.Authenticated
        this.session = session
    }

    fun replaceSession(session: GatewaySession?) {
        this.session = session
    }

    /**
     * Force [currentStatus] to start returning [next] on subsequent reads.
     * Used to model a gateway whose status flipped out of band (e.g. signOut
     * cleared the session — next status read should be NotAuthenticated).
     */
    fun setStatus(next: AuthGatewayStatus) {
        status = next
    }

    override suspend fun awaitInitialization() {
        onAwaitInitialization()
    }

    override fun currentStatus(): AuthGatewayStatus {
        statusReads++
        // If the test supplied a per-poll sequence, drain that first.
        return statusIterator?.takeIf { it.hasNext() }?.next() ?: status
    }

    override fun currentSession(): GatewaySession? = session

    override suspend fun signInAnonymously() {
        signInAnonymouslyCalls++
        onSignInAnonymously()
    }

    override suspend fun refreshSession() {
        refreshSessionCalls++
        onRefreshSession()
    }

    override suspend fun signInWithEmail(email: String, password: String): Unit =
        error("signInWithEmail not stubbed for these tests")

    override suspend fun signUpWithEmail(email: String, password: String): Unit =
        error("signUpWithEmail not stubbed for these tests")

    override suspend fun resendVerificationEmail(email: String): Unit =
        error("resendVerificationEmail not stubbed for these tests")

    override suspend fun resetPasswordForEmail(email: String): Unit =
        error("resetPasswordForEmail not stubbed for these tests")

    override suspend fun signOut() {
        signOutCalls++
        onSignOut()
    }

    override suspend fun linkOAuthIdentity(provider: OAuthProvider) {
        linkOAuthIdentityCalls++
        onLinkOAuthIdentity(provider)
    }

    override suspend fun signInWithOAuth(provider: OAuthProvider) {
        signInWithOAuthCalls++
        onSignInWithOAuth(provider)
    }

    override suspend fun completeOAuthRedirect(url: String) {
        completeOAuthRedirectCalls++
        onCompleteOAuthRedirect(url)
    }

    override suspend fun hydrateCurrentUser() {
        hydrateCurrentUserCalls++
        onHydrateCurrentUser()
    }

    override fun isOAuthRedirect(url: String): Boolean =
        url.startsWith("cards://login-callback")

    override suspend fun signInWithAppleIdToken(idToken: String, nonce: String): Unit =
        error("signInWithAppleIdToken not stubbed for these tests")

    override suspend fun linkAppleIdToken(idToken: String, nonce: String): Unit =
        error("linkAppleIdToken not stubbed for these tests")

    override suspend fun signInWithGoogleIdToken(idToken: String, nonce: String?): Unit =
        error("signInWithGoogleIdToken not stubbed for these tests")

    override suspend fun linkEmailIdentity(email: String, password: String): Unit =
        error("linkEmailIdentity not stubbed for these tests")
}
