package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AccountCreationState
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.DeleteAccountOutcome
import com.dangerfield.cards.libraries.identity.auth.GuestAccountCreator
import com.dangerfield.cards.libraries.identity.auth.LinkEmailIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.PendingIdentity
import com.dangerfield.cards.libraries.identity.auth.RefreshOutcome
import com.dangerfield.cards.libraries.identity.auth.ResendOutcome
import com.dangerfield.cards.libraries.identity.auth.SendResetOutcome
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.auth.SignUpOutcome
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Pins the [GuestSessionHealer] eligibility matrix: which trigger states lead to
 * a mint and which short-circuit. Each test maps to one decision branch.
 */
class GuestSessionHealerTest : CoroutineTest() {

    @Test
    fun skip_whenAlreadyAuthenticated() = runUnitTest {
        val creator = FakeGuestAccountCreator()
        val healer = build(
            auth = FakeAuth(current = authed()),
            creator = creator,
        )

        healer.onForeground(AppEvent.OnForeground(isColdBoot = false))
        advanceUntilIdle()

        assertEquals(0, creator.ensureCalls, "a live session must not mint")
    }

    @Test
    fun skip_whenRetryRecoversSession() = runUnitTest {
        val creator = FakeGuestAccountCreator()
        val healer = build(
            auth = FakeAuth(current = AuthState.Unauthenticated(), retryResult = authed()),
            creator = creator,
        )

        healer.onConnectivityRegained(AppEvent.ConnectivityRegained)
        advanceUntilIdle()

        assertEquals(0, creator.ensureCalls, "a recovered session must not mint")
    }

    @Test
    fun skip_whenSessionExpired() = runUnitTest {
        val creator = FakeGuestAccountCreator()
        val healer = build(
            auth = FakeAuth(
                current = AuthState.Unauthenticated(),
                retryResult = AuthState.Unauthenticated(reason = AuthState.Unauthenticated.Reason.SessionExpired),
            ),
            creator = creator,
            onboarded = true,
        )

        healer.onForeground(AppEvent.OnForeground(isColdBoot = false))
        advanceUntilIdle()

        assertEquals(0, creator.ensureCalls, "a server-killed session routes to re-auth, not heal")
    }

    @Test
    fun skip_whenSignedOut() = runUnitTest {
        val creator = FakeGuestAccountCreator()
        val healer = build(
            auth = FakeAuth(
                current = AuthState.Unauthenticated(),
                retryResult = AuthState.Unauthenticated(reason = AuthState.Unauthenticated.Reason.SignedOut),
            ),
            creator = creator,
            onboarded = true,
        )

        healer.onForeground(AppEvent.OnForeground(isColdBoot = false))
        advanceUntilIdle()

        assertEquals(0, creator.ensureCalls, "a deliberate sign-out must not be resurrected")
    }

    @Test
    fun skip_whenNotOnboarded() = runUnitTest {
        val creator = FakeGuestAccountCreator()
        val healer = build(
            auth = FakeAuth(current = AuthState.Unauthenticated(), retryResult = AuthState.Unauthenticated()),
            creator = creator,
            onboarded = false,
        )

        healer.onColdBoot(AppEvent.ColdBoot)
        advanceUntilIdle()

        assertEquals(0, creator.ensureCalls, "pre-onboarding there's no identity owed")
    }

    @Test
    fun skip_whenOffline() = runUnitTest {
        val creator = FakeGuestAccountCreator()
        val healer = build(
            auth = FakeAuth(current = AuthState.Unauthenticated(), retryResult = AuthState.Unauthenticated()),
            creator = creator,
            onboarded = true,
            offline = true,
        )

        healer.onConnectivityRegained(AppEvent.ConnectivityRegained)
        advanceUntilIdle()

        assertEquals(0, creator.ensureCalls, "can't mint offline")
    }

    @Test
    fun mints_whenStranded_onboardedOnlineSessionless() = runUnitTest {
        val creator = FakeGuestAccountCreator(result = AccountCreationState.Succeeded)
        val healer = build(
            auth = FakeAuth(current = AuthState.Unauthenticated(), retryResult = AuthState.Unauthenticated()),
            creator = creator,
            onboarded = true,
            offline = false,
            profile = Profile.Fallback(id = "local-1"),
        )

        healer.onForeground(AppEvent.OnForeground(isColdBoot = false))
        advanceUntilIdle()

        assertEquals(1, creator.ensureCalls, "the stranded case mints")
        // Fallback profile → server generates a fresh identity (null fields).
        assertNull(creator.lastFallback?.displayName)
    }

    @Test
    fun coldBootForeground_isNotDoubleHandled() = runUnitTest {
        val creator = FakeGuestAccountCreator(result = AccountCreationState.Succeeded)
        val healer = build(
            auth = FakeAuth(current = AuthState.Unauthenticated(), retryResult = AuthState.Unauthenticated()),
            creator = creator,
            onboarded = true,
        )

        // A cold-boot foreground is owned by onColdBoot; the foreground handler
        // must skip it so launch doesn't run the decision twice.
        healer.onForeground(AppEvent.OnForeground(isColdBoot = true))
        advanceUntilIdle()

        assertEquals(0, creator.ensureCalls, "cold-boot foreground is skipped (onColdBoot owns it)")
    }

    @Test
    fun concurrentHeals_runTheDecisionOnce() = runUnitTest {
        val gate = CompletableDeferred<Unit>()
        val creator = FakeGuestAccountCreator(result = AccountCreationState.Succeeded, gate = gate)
        val healer = build(
            auth = FakeAuth(current = AuthState.Unauthenticated(), retryResult = AuthState.Unauthenticated()),
            creator = creator,
            onboarded = true,
        )

        healer.heal("first")
        advanceUntilIdle() // first heal is parked inside ensureSession
        healer.heal("second")
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, creator.ensureCalls, "overlapping triggers must not run the decision twice")
    }

    @Test
    fun heal_runsAgainAfterThePreviousRunCompletes() = runUnitTest {
        val creator = FakeGuestAccountCreator(result = AccountCreationState.Succeeded)
        val healer = build(
            auth = FakeAuth(current = AuthState.Unauthenticated(), retryResult = AuthState.Unauthenticated()),
            creator = creator,
            onboarded = true,
        )

        healer.heal("first")
        advanceUntilIdle()
        healer.heal("second")
        advanceUntilIdle()

        assertEquals(2, creator.ensureCalls, "single-flight is skip-if-running, not once-ever")
    }

    @Test
    fun stopsForRecovery_insteadOfMintingOverACachedAccount() = runUnitTest {
        // AUTH-19: a cached server profile with no revivable session means this
        // device HAS a real account whose token storage failed (the TestFlight
        // upgrade case). Minting a fresh guest silently strands that account's
        // chips/XP — the healer must stop and declare the session unrecoverable
        // so the app routes to the explicit recovery screen.
        val creator = FakeGuestAccountCreator()
        val auth = FakeAuth(current = AuthState.Unauthenticated(), retryResult = AuthState.Unauthenticated())
        val healer = build(
            auth = auth,
            creator = creator,
            onboarded = true,
            profile = Profile.Authenticated(
                id = "u1",
                displayName = "Foxy",
                avatarEmoji = "🦊",
                avatarBackgroundColor = "#ff6b35",
                email = null,
                isAnonymous = true,
                createdAt = Instant.fromEpochMilliseconds(0),
            ),
        )

        healer.onForeground(AppEvent.OnForeground(isColdBoot = false))
        advanceUntilIdle()

        assertEquals(0, creator.ensureCalls, "must never mint over a cached account")
        assertEquals(
            listOf(true),
            auth.markedUnrecoverable,
            "recovery is declared once, carrying the stranded account's anonymity",
        )
    }

    @Test
    fun offline_withCachedAuthenticatedProfile_neverTearsDown() = runUnitTest {
        // AUTH-30: unreachable-backend cold boot. Offline AND a cached anonymous
        // guest profile survives. Being offline we CANNOT know the session is dead
        // — the backend is just unreachable — so the healer must not declare it
        // unrecoverable (which tears down + permanently deletes the guest). It
        // waits for the connectivity-regained edge instead.
        val creator = FakeGuestAccountCreator()
        val auth = FakeAuth(
            current = AuthState.Unauthenticated(),
            retryResult = AuthState.Unauthenticated(), // reason None
        )
        val healer = build(
            auth = auth,
            creator = creator,
            onboarded = true,
            offline = true,
            profile = cachedGuest(),
        )

        healer.onColdBoot(AppEvent.ColdBoot)
        advanceUntilIdle()

        assertEquals(0, creator.ensureCalls, "offline: must not mint")
        assertEquals(
            emptyList<Boolean>(),
            auth.markedUnrecoverable,
            "offline must never declare the cached guest session unrecoverable",
        )
    }

    @Test
    fun unreachableReason_withCachedAuthenticatedProfile_neverTearsDown_evenWhenFlagOptimisticallyOnline() =
        runUnitTest {
            // The durable half of AUTH-30: the resolve settled Reason.Unreachable
            // (exhausted on transient refresh). The healer must skip on the reason
            // alone, WITHOUT depending on appState.isOffline having flipped — the
            // flag starts optimistically false and may not have resolved on a fast
            // boot, so the reorder-the-offline-gate fix is not sufficient on its own.
            val creator = FakeGuestAccountCreator()
            val auth = FakeAuth(
                current = AuthState.Unauthenticated(),
                retryResult = AuthState.Unauthenticated(
                    reason = AuthState.Unauthenticated.Reason.Unreachable,
                ),
            )
            val healer = build(
                auth = auth,
                creator = creator,
                onboarded = true,
                offline = false, // flag still optimistically online
                profile = cachedGuest(),
            )

            healer.onColdBoot(AppEvent.ColdBoot)
            advanceUntilIdle()

            assertEquals(0, creator.ensureCalls, "unreachable: must not mint")
            assertEquals(
                emptyList<Boolean>(),
                auth.markedUnrecoverable,
                "an unreachable backend must never declare the cached guest session unrecoverable",
            )
        }

    private fun cachedGuest() = Profile.Authenticated(
        id = "u1",
        displayName = "Foxy",
        avatarEmoji = "🦊",
        avatarBackgroundColor = "#ff6b35",
        email = null,
        isAnonymous = true,
        createdAt = Instant.fromEpochMilliseconds(0),
    )

    private fun build(
        auth: AuthRepository,
        creator: GuestAccountCreator,
        onboarded: Boolean = true,
        offline: Boolean = false,
        profile: Profile = Profile.Fallback(id = "local"),
    ) = GuestSessionHealer(
        authRepositoryProvider = { auth },
        guestAccountCreatorProvider = { creator },
        profileRepositoryProvider = { FakeProfile(profile) },
        appCache = FakeAppCache(onboarded),
        appState = FakeAppState(MutableStateFlow(offline)),
        appScope = AppCoroutineScope(dispatchers),
    )

    private fun authed() = AuthState.Authenticated(userId = "u1", isAnonymous = true, email = null)

    private class FakeAppState(
        override val isOffline: MutableStateFlow<Boolean>,
    ) : AppState {
        override val isBlockActive: StateFlow<Boolean> = MutableStateFlow(false)
    }

    private class FakeAppCache(onboarded: Boolean) : AppCache {
        private val state = MutableStateFlow(AppData(hasUserOnboarded = onboarded))
        override val updates: Flow<AppData> = state
        override suspend fun get(): AppData = state.value
        override suspend fun set(value: AppData) { state.value = value }
        override suspend fun clear() { state.value = AppData() }
    }

    private class FakeProfile(private val profile: Profile) : ProfileRepository {
        override suspend fun current(): Profile = profile
        override fun observe(): Flow<Profile> = emptyFlow()
        override suspend fun update(
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ): UpdateProfileOutcome = error("unused")
        override suspend fun fetchAvatarPack(): AvatarPackOutcome = error("unused")
    }

    private class FakeGuestAccountCreator(
        private val result: AccountCreationState = AccountCreationState.Succeeded,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : GuestAccountCreator {
        var ensureCalls = 0
            private set
        var lastFallback: PendingIdentity? = null
            private set

        override val state: StateFlow<AccountCreationState> = MutableStateFlow(AccountCreationState.Idle)
        override fun start(identity: PendingIdentity) = error("unused")
        override fun retry() = error("unused")
        override suspend fun awaitTerminal(): AccountCreationState = result
        override suspend fun ensureSession(fallbackIdentity: PendingIdentity): AccountCreationState {
            ensureCalls++
            lastFallback = fallbackIdentity
            gate?.await()
            return result
        }
    }

    private class FakeAuth(
        private val current: AuthState,
        private val retryResult: AuthState = AuthState.Unauthenticated(),
    ) : AuthRepository {
        val markedUnrecoverable = mutableListOf<Boolean>()
        override suspend fun current(): AuthState = current
        override suspend fun retry(): AuthState = retryResult
        override suspend fun markSessionUnrecoverable(wasAnonymous: Boolean) {
            markedUnrecoverable += wasAnonymous
        }
        override fun observe(): Flow<AuthState> = emptyFlow()
        override suspend fun signInWithEmail(email: String, password: String): SignInOutcome = error("unused")
        override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome = error("unused")
        override suspend fun refreshSession(): RefreshOutcome = error("unused")
        override suspend fun resendVerificationEmail(email: String): ResendOutcome = error("unused")
        override suspend fun sendPasswordResetEmail(email: String): SendResetOutcome = error("unused")
        override suspend fun signOut() = error("unused")
        override suspend fun deleteAccount(): DeleteAccountOutcome = error("unused")
        override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome = error("unused")
        override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome = error("unused")
        override suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome = error("unused")
    }
}
