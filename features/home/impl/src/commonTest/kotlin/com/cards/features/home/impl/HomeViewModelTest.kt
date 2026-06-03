package com.dangerfield.cards.features.home.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.GameplayFrame
import com.dangerfield.cards.libraries.rooms.GetActiveRoomsOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import com.dangerfield.cards.libraries.rooms.RoomRepository
import com.dangerfield.cards.libraries.rooms.RoomStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [HomeViewModel]'s init-time fan-in. HomeViewModel is the app
 * entry point's surface — three repositories (profile, progression,
 * chips) are subscribed at construction and their emissions hydrate the
 * home state. Pinning here protects against silent regressions in:
 *  - chip / xp updates not reaching the home badge after a hand,
 *  - anonymous flag flipping when the user claims their account,
 *  - display name updates propagating after edit-profile (now sourced
 *    from [ProfileRepository], not the deleted UserRepository).
 */
class HomeViewModelTest : CoroutineTest() {

    @Test
    fun authenticatedProfile_surfacesNameAndAnonFlag() = runUnitTest {
        val profile = FakeProfileRepository(
            initial = authenticatedProfile(displayName = "QuietAce72", isAnonymous = true),
        )
        val vm = buildVm(profile = profile)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.userName == null) last = awaitItem()
            assertEquals("QuietAce72", last.userName)
            assertEquals(true, last.isAnonymous)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun fallbackProfile_yieldsAnonymousTrue_andNullName() = runUnitTest {
        // Home renders gracefully when the profile hasn't resolved to a
        // server-backed row — name stays null, anon flag stays true.
        val profile = FakeProfileRepository(initial = Profile.Fallback(id = "anon"))
        val vm = buildVm(profile = profile)
        vm.stateFlow.test {
            val s = awaitItem()
            assertNull(s.userName)
            assertEquals(true, s.isAnonymous)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun claimedProfile_isAnonymousIsFalse() = runUnitTest {
        val profile = FakeProfileRepository(
            initial = authenticatedProfile(displayName = "Real Name", isAnonymous = false),
        )
        val vm = buildVm(profile = profile)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.userName == null) last = awaitItem()
            assertEquals(false, last.isAnonymous)
            assertEquals("Real Name", last.userName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun profileUpdate_propagatesToState() = runUnitTest {
        // PATCH /v1/me → ProfileRepository emits a new Profile → home
        // state picks up the new display name without any explicit refresh.
        val profile = FakeProfileRepository(
            initial = authenticatedProfile(displayName = "OldName", isAnonymous = false),
        )
        val vm = buildVm(profile = profile)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.userName != "OldName") last = awaitItem()

            profile.profile.value =
                authenticatedProfile(displayName = "NewName", isAnonymous = false)

            while (last.userName != "NewName") last = awaitItem()
            assertEquals("NewName", last.userName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun chipsBalance_updates_propagateToState() = runUnitTest {
        val chips = FakeChipsRepository(initial = 10_000L)
        val vm = buildVm(chips = chips)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.chips != 10_000L) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // A hand played + chips applied — home badge must update.
        chips.balance.value = 12_500L
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.chips != 12_500L) last = awaitItem()
            assertEquals(12_500L, last.chips)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun progression_updates_propagateToState() = runUnitTest {
        val progression = FakeProgressionRepository(
            initial = Progression.Empty.copy(totalXp = 250L),
        )
        val vm = buildVm(progression = progression)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.levelProgress.totalXp != 250L) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        progression.progression.value = progression.progression.value.copy(totalXp = 1_750L)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.levelProgress.totalXp != 1_750L) last = awaitItem()
            assertEquals(1_750L, last.levelProgress.totalXp)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun activeRooms_success_populatesState() = runUnitTest {
        val room = sampleRoom(code = "WXYZ12")
        val rooms = FakeRoomRepository(
            activeRoomsOutcome = GetActiveRoomsOutcome.Success(listOf(room)),
        )
        val vm = buildVm(rooms = rooms)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.activeRooms.isEmpty()) last = awaitItem()
            assertEquals(listOf(ActiveRoomSummary(code = "WXYZ12")), last.activeRooms)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun activeRooms_networkError_keepsEmpty() = runUnitTest {
        // The home banner must stay silent when /v1/me/active-rooms fails so
        // we don't pop a "you have an ongoing game" affordance keyed to nothing.
        val rooms = FakeRoomRepository(
            activeRoomsOutcome = GetActiveRoomsOutcome.NetworkError(RuntimeException("boom")),
        )
        val profile = FakeProfileRepository(
            initial = authenticatedProfile(displayName = "Tester", isAnonymous = true),
        )
        val vm = buildVm(rooms = rooms, profile = profile)
        vm.stateFlow.test {
            // Wait until the profile emission has landed — that's the
            // observable signal that init has drained.
            var last = awaitItem()
            while (last.userName == null) last = awaitItem()
            assertTrue(last.activeRooms.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun activeRooms_multiple_keepsNewest_andLeavesOlderOnes() = runUnitTest {
        // A healthy steady state is exactly one active room per user.
        // Two means the previous session crashed / WS dropped without a clean
        // tear-down; we should converge to a single room rather than render
        // a stack of banners that all race when the user picks one.
        val newer = sampleRoom(code = "NEW111", createdAtEpochMs = 1_700_000_002_000)
        val older = sampleRoom(code = "OLD000", createdAtEpochMs = 1_700_000_000_000)
        val middle = sampleRoom(code = "MID222", createdAtEpochMs = 1_700_000_001_000)
        val rooms = FakeRoomRepository(
            activeRoomsOutcome = GetActiveRoomsOutcome.Success(listOf(older, newer, middle)),
        )
        val vm = buildVm(rooms = rooms)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.activeRooms.isEmpty()) last = awaitItem()
            assertEquals(
                listOf(ActiveRoomSummary("NEW111")), last.activeRooms,
                "newest active room (by createdAt) is the one the banner keeps",
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            setOf("OLD000", "MID222"), rooms.leaveCalls.toSet(),
            "every stale room is leave-queued; order doesn't matter",
        )
    }

    @Test
    fun activeRooms_singleRoom_doesNotIssueAnyLeave() = runUnitTest {
        // Single active room is the steady state. No cleanup leave calls.
        val only = sampleRoom(code = "ONLY11")
        val rooms = FakeRoomRepository(
            activeRoomsOutcome = GetActiveRoomsOutcome.Success(listOf(only)),
        )
        val vm = buildVm(rooms = rooms)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.activeRooms.isEmpty()) last = awaitItem()
            assertEquals(listOf(ActiveRoomSummary("ONLY11")), last.activeRooms)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(
            rooms.leaveCalls.isEmpty(),
            "single-room steady state must not surface any cleanup leave",
        )
    }

    @Test
    fun forfeit_optimisticallyRemovesRoom_andCallsLeave() = runUnitTest {
        val only = sampleRoom(code = "AAA111")
        val rooms = FakeRoomRepository(
            activeRoomsOutcome = GetActiveRoomsOutcome.Success(listOf(only)),
        )
        val vm = buildVm(rooms = rooms)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.activeRooms.isEmpty()) last = awaitItem()

            vm.takeAction(HomeAction.Forfeit(code = "AAA111"))

            while (last.activeRooms.isNotEmpty()) last = awaitItem()
            assertEquals(listOf("AAA111"), rooms.leaveCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun welcomeGate_requiresGrantInfo_waitsForChips_thenFires() = runUnitTest {
        // requiresGrantInfo=true + Profile.Authenticated, but chips not yet
        // hydrated → don't fire. The dialog's job is to reveal the real
        // number, so the gate waits for the balance before firing.
        val profile = FakeProfileRepository(
            initial = authenticatedProfile(displayName = "FreshInstall", isAnonymous = true),
        )
        val chips = FakeChipsRepository(initial = null)
        val appCache = FakeAppCache(initial = AppData(requiresGrantInfo = true))
        val vm = buildVm(profile = profile, chips = chips, appCache = appCache)

        vm.eventFlow.test {
            expectNoEvents()
            // Wallet sync lands with the authoritative balance.
            chips.balance.value = 10_500L
            val event = awaitItem()
            assertTrue(event is HomeEvent.OpenWelcomeDialog)
            assertEquals(10_500L, event.payload.chips)
            assertEquals("FreshInstall", event.payload.displayName)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            false, appCache.get().requiresGrantInfo,
            "gate must clear requiresGrantInfo at emit time so it doesn't re-fire",
        )
    }

    @Test
    fun welcomeGate_requiresGrantInfoFalse_doesNotFire() = runUnitTest {
        // Returning user (or a user who already saw the reveal in onboarding):
        // requiresGrantInfo=false → never fire, even with a hydrated balance.
        val profile = FakeProfileRepository(
            initial = authenticatedProfile(displayName = "Returning", isAnonymous = false),
        )
        val chips = FakeChipsRepository(initial = 250_000L)
        val appCache = FakeAppCache(initial = AppData(requiresGrantInfo = false))
        val vm = buildVm(profile = profile, chips = chips, appCache = appCache)

        vm.eventFlow.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun welcomeGate_profileFallback_doesNotFire_butFiresOnceProfileResolves() = runUnitTest {
        // Regression: previous gate flipped `isFirstEverSession` to false the
        // first time the app backgrounded, which permanently locked a user
        // out of the welcome if the /v1/me call failed (Fly cold-boot timeout
        // → Profile.Fallback). Now the gate just waits for an Authenticated
        // profile + hydrated chips — when both arrive, the welcome fires.
        val profile = FakeProfileRepository(initial = Profile.Fallback(id = "anon"))
        val chips = FakeChipsRepository(initial = 10_000L)
        val appCache = FakeAppCache(initial = AppData(requiresGrantInfo = true))
        val vm = buildVm(profile = profile, chips = chips, appCache = appCache)

        vm.eventFlow.test {
            expectNoEvents()
            // Server eventually responds — profile resolves to Authenticated.
            profile.emit(authenticatedProfile(displayName = "Eventually", isAnonymous = true))
            val event = awaitItem()
            assertTrue(event is HomeEvent.OpenWelcomeDialog)
            assertEquals("Eventually", event.payload.displayName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun forfeit_networkError_rehydratesFromServer() = runUnitTest {
        // A leave that fails over the wire must NOT silently drop the room from
        // the user's view — the server's truth is still "you're in." Reload.
        val original = sampleRoom(code = "AAA111")
        val rooms = FakeRoomRepository(
            activeRoomsOutcome = GetActiveRoomsOutcome.Success(listOf(original)),
            leaveOutcome = LeaveRoomOutcome.NetworkError(RuntimeException("boom")),
        )
        val vm = buildVm(rooms = rooms)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.activeRooms.isEmpty()) last = awaitItem()

            vm.takeAction(HomeAction.Forfeit(code = "AAA111"))

            // The optimistic drop + rehydrate is conflated by StateFlow into the
            // same end-state — so we assert the final value and that the leave
            // failure triggered a second active-rooms fetch.
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(ActiveRoomSummary("AAA111")), vm.stateFlow.value.activeRooms)
        assertTrue(rooms.getActiveRoomsCalls >= 2, "Failure must re-query active rooms")
        assertEquals(listOf("AAA111"), rooms.leaveCalls)
    }

    // ---------- scaffolding ----------

    private fun buildVm(
        progression: FakeProgressionRepository = FakeProgressionRepository(),
        achievements: FakeAchievementRepository = FakeAchievementRepository(),
        chips: FakeChipsRepository = FakeChipsRepository(),
        rooms: FakeRoomRepository = FakeRoomRepository(),
        profile: FakeProfileRepository = FakeProfileRepository(),
        appCache: FakeAppCache = FakeAppCache(),
    ): HomeViewModel = HomeViewModel(
        progressionRepository = progression,
        achievementRepository = achievements,
        chipsRepository = chips,
        roomRepository = rooms,
        profileRepository = profile,
        appCache = appCache,
        appScope = AppCoroutineScope(dispatchers),
    )

    private fun sampleRoom(
        code: String,
        createdAtEpochMs: Long = 1_700_000_000_000,
    ): Room = Room(
        code = code,
        hostUserId = "11111111-1111-1111-1111-111111111111",
        createdAtEpochMs = createdAtEpochMs,
        maxSeats = 4,
        status = RoomStatus.Playing,
        members = emptyList(),
    )

    private fun authenticatedProfile(
        displayName: String,
        isAnonymous: Boolean,
        id: String = "00000000-0000-0000-0000-000000000001",
        avatarEmoji: String = "🦊",
        avatarBackgroundColor: String? = "#F6B26B",
    ): Profile.Authenticated = Profile.Authenticated(
        id = id,
        displayName = displayName,
        avatarEmoji = avatarEmoji,
        avatarBackgroundColor = avatarBackgroundColor,
        email = if (isAnonymous) null else "$displayName@example.com",
        isAnonymous = isAnonymous,
        createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
    )

    private class FakeChipsRepository(
        initial: Long? = 10_000L,
    ) : ChipsRepository {
        val balance = MutableStateFlow(initial)
        override fun observeBalance(): Flow<Long?> = balance
        override suspend fun getBalance(): Long? = balance.value
        override suspend fun addChips(amount: Long, reason: String, idempotencyKey: String?) {
            balance.value = (balance.value ?: 0L) + amount
        }
        override suspend fun subtractChips(amount: Long, reason: String, idempotencyKey: String?) {
            balance.value = (balance.value ?: 0L) - amount
        }
        override suspend fun setBalance(authoritativeBalance: Long) {
            balance.value = authoritativeBalance
        }
        override suspend fun deleteAll() {
            balance.value = null
        }
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
    }

    private class FakeRoomRepository(
        private val activeRoomsOutcome: GetActiveRoomsOutcome = GetActiveRoomsOutcome.Success(emptyList()),
        private val leaveOutcome: LeaveRoomOutcome = LeaveRoomOutcome.Success,
    ) : RoomRepository {
        var getActiveRoomsCalls: Int = 0
            private set
        val leaveCalls: MutableList<String> = mutableListOf()

        override suspend fun createRoom(maxSeats: Int?): CreateRoomOutcome =
            CreateRoomOutcome.NetworkError(RuntimeException("not used"))
        override suspend fun joinRoom(code: String): JoinRoomOutcome =
            JoinRoomOutcome.NetworkError(RuntimeException("not used"))
        override suspend fun leaveRoom(code: String): LeaveRoomOutcome {
            leaveCalls += code
            return leaveOutcome
        }
        override suspend fun getActiveRooms(): GetActiveRoomsOutcome {
            getActiveRoomsCalls += 1
            return activeRoomsOutcome
        }
        override fun connect(code: String): RoomConnectionHandle = object : RoomConnectionHandle {
            override val connection: Flow<RoomConnection> = flow { }
            override val gameplayFrames: Flow<GameplayFrame> = flow { }
            override suspend fun send(frame: ClientFrame) = Unit
        }
    }

    private class FakeProgressionRepository(
        initial: Progression = Progression.Empty,
    ) : ProgressionRepository {
        val progression = MutableStateFlow(initial)
        override fun observeProgression(): Flow<Progression> = progression
        override suspend fun getProgression(): Progression = progression.value
        override suspend fun awardForHand(summary: HandResultSummary): List<XpEvent> =
            error("awardForHand not used by HomeViewModel")
        override suspend fun applyAchievementXp(delta: Int, description: String?): XpEvent =
            error("applyAchievementXp not used by HomeViewModel")
        override suspend fun deleteAll() { /* not used */ }
        override suspend fun debugSetTotalXp(totalXp: Long) {
            progression.value = progression.value.copy(totalXp = totalXp)
        }
    }

    private class FakeProfileRepository(
        initial: Profile = Profile.Fallback(id = "anon"),
    ) : ProfileRepository {
        val profile = MutableStateFlow(initial)
        suspend fun emit(next: Profile) { profile.emit(next) }
        override suspend fun current(): Profile = profile.value
        override fun observe(): Flow<Profile> = profile
        override suspend fun update(
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ): UpdateProfileOutcome = error("not used by HomeViewModel")
        override suspend fun fetchAvatarPack(): AvatarPackOutcome =
            error("not used by HomeViewModel")
    }

    private class FakeAchievementRepository(
        initial: AchievementProgress = AchievementProgress.Empty,
    ) : AchievementRepository {
        val progress = MutableStateFlow(initial)
        override fun observeProgress(): Flow<AchievementProgress> = progress
        override suspend fun getProgress(): AchievementProgress = progress.value
        override suspend fun recordHand(
            summary: HandResultSummary,
            context: AchievementHandContext,
        ): List<EarnedAchievement> = error("recordHand not used by HomeViewModel")
        override suspend fun recordTutorialComplete(): EarnedAchievement? =
            error("recordTutorialComplete not used by HomeViewModel")
        override suspend fun deleteAll() { /* not used */ }
    }

    private class FakeAppCache(initial: AppData = AppData()) : AppCache {
        private val state = MutableStateFlow(initial)
        override val updates: Flow<AppData> = state
        override suspend fun get(): AppData = state.value
        override suspend fun set(value: AppData) { state.value = value }
        override suspend fun clear() { state.value = AppData() }
    }
}
