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
import kotlinx.coroutines.yield
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
    fun levelUp_freshWatermark_seedsSilently_noCelebration() = runUnitTest {
        // lastCelebratedLevel == 0 (unset). The current level (1, empty
        // progression) seeds the watermark silently — no celebration for a
        // level the user already had.
        val progression = FakeProgressionRepository(initial = Progression.Empty)
        val appCache = FakeAppCache() // lastCelebratedLevel = 0
        val vm = buildVm(progression = progression, appCache = appCache)
        vm.stateFlow.test {
            var last = awaitItem()
            while (appCache.get().lastCelebratedLevel == 0) last = awaitItem()
            assertEquals(1, appCache.get().lastCelebratedLevel)
            assertNull(last.levelUpCelebration)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun levelUp_switchIntoLeveledAccount_seedsToCurrent_noCelebration() = runUnitTest {
        // Account switch wipes the watermark to 0; the switched-in account is
        // already level 2 (150 XP). Seeding must catch up silently rather than
        // blasting a celebration for a level the user already holds.
        val progression = FakeProgressionRepository(initial = Progression.Empty.copy(totalXp = 150L))
        val appCache = FakeAppCache()
        val vm = buildVm(progression = progression, appCache = appCache)
        vm.stateFlow.test {
            var last = awaitItem()
            while (appCache.get().lastCelebratedLevel == 0) last = awaitItem()
            assertEquals(2, appCache.get().lastCelebratedLevel)
            assertNull(last.levelUpCelebration)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun levelUp_crossingWatermark_showsCelebration_thenDismissAdvances() = runUnitTest {
        val progression = FakeProgressionRepository(initial = Progression.Empty)
        val appCache = FakeAppCache()
        val vm = buildVm(progression = progression, appCache = appCache)
        vm.stateFlow.test {
            var last = awaitItem()
            // Seed settles to level 1.
            while (appCache.get().lastCelebratedLevel != 1) last = awaitItem()

            // Earn enough XP to reach level 2 → celebration surfaces.
            progression.progression.value = progression.progression.value.copy(totalXp = 150L)
            while (last.levelUpCelebration == null) last = awaitItem()
            assertEquals(2, last.levelUpCelebration)

            // Dismiss advances the watermark and clears the overlay.
            vm.takeAction(HomeAction.DismissLevelUp)
            while (last.levelUpCelebration != null) last = awaitItem()
            assertEquals(2, appCache.get().lastCelebratedLevel)
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
    fun activeRooms_liveUpdate_reflectsWithoutRefetch() = runUnitTest {
        // The banner is reactive: a room appearing in the repository's
        // observed set (e.g. a join landing) shows on Home with no explicit
        // refresh, and a room leaving the set clears it.
        val rooms = FakeRoomRepository(
            activeRoomsOutcome = GetActiveRoomsOutcome.Success(emptyList()),
        )
        val vm = buildVm(rooms = rooms)
        val callsAfterSeed = rooms.getActiveRoomsCalls
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.activeRooms.isNotEmpty()) last = awaitItem()

            rooms.emitActiveRooms(listOf(sampleRoom(code = "JOIN99")))
            while (last.activeRooms.isEmpty()) last = awaitItem()
            assertEquals(listOf(ActiveRoomSummary("JOIN99")), last.activeRooms)

            rooms.emitActiveRooms(emptyList())
            while (last.activeRooms.isNotEmpty()) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            callsAfterSeed, rooms.getActiveRoomsCalls,
            "live updates must not trigger a re-fetch",
        )
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
    fun activeRooms_multiple_bannerShowsNewest() = runUnitTest {
        // A healthy steady state is exactly one active room per user. If more
        // than one slips through (a prior session dropped without a clean
        // tear-down) the banner surfaces the newest rather than a stack of
        // racing banners. The server's seat-grace timer reaps the stale ones.
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
                "newest active room (by createdAt) is the one the banner shows",
            )
            cancelAndIgnoreRemainingEvents()
        }
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
    fun welcomeGate_walletJustCreated_waitsForChips_thenFires() = runUnitTest {
        // walletJustCreated=true + Profile.Authenticated, but chips not yet
        // hydrated → don't fire. The dialog's job is to reveal the real
        // number, so the gate waits for the balance before firing.
        val profile = FakeProfileRepository(
            initial = authenticatedProfile(displayName = "FreshInstall", isAnonymous = true),
        )
        val chips = FakeChipsRepository(initial = null, walletJustCreatedInitial = true)
        val appCache = FakeAppCache() // didSeeInitialGrantInOnboarding = false
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
            true, appCache.get().didSeeInitialGrantInOnboarding,
            "gate must mark the grant seen at emit time so it doesn't re-fire",
        )
    }

    @Test
    fun welcomeGate_walletNotJustCreated_doesNotFire() = runUnitTest {
        // Returning user / switched-into account: walletJustCreated=false →
        // never fire, even with a hydrated balance. This is the leak fix —
        // the signal is live + server-sourced, false for a pre-existing wallet.
        val profile = FakeProfileRepository(
            initial = authenticatedProfile(displayName = "Returning", isAnonymous = false),
        )
        val chips = FakeChipsRepository(initial = 250_000L, walletJustCreatedInitial = false)
        val appCache = FakeAppCache()
        val vm = buildVm(profile = profile, chips = chips, appCache = appCache)

        vm.eventFlow.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun welcomeGate_alreadySeenInOnboarding_doesNotFire() = runUnitTest {
        // Wallet was just created, but onboarding already revealed the number
        // (didSeeInitialGrantInOnboarding=true) → the Home dialog must not
        // repeat it.
        val profile = FakeProfileRepository(
            initial = authenticatedProfile(displayName = "SawItInOnboarding", isAnonymous = true),
        )
        val chips = FakeChipsRepository(initial = 10_000L, walletJustCreatedInitial = true)
        val appCache = FakeAppCache(initial = AppData(didSeeInitialGrantInOnboarding = true))
        val vm = buildVm(profile = profile, chips = chips, appCache = appCache)

        vm.eventFlow.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun welcomeGate_profileFallback_doesNotFire_butFiresOnceProfileResolves() = runUnitTest {
        // Gate waits for an Authenticated profile + hydrated chips — when both
        // arrive (and the wallet was just created, not yet revealed), it fires.
        val profile = FakeProfileRepository(initial = Profile.Fallback(id = "anon"))
        val chips = FakeChipsRepository(initial = 10_000L, walletJustCreatedInitial = true)
        val appCache = FakeAppCache()
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
    fun forfeit_networkError_keepsRoomVisible() = runUnitTest {
        // A leave that fails over the wire must NOT silently drop the room from
        // the user's view — the server's truth is still "you're in." The banner
        // is now driven by the repository's observed room set, which a failed
        // leave leaves untouched, so the room simply stays visible — no
        // optimistic drop to undo, no re-query needed.
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
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(ActiveRoomSummary("AAA111")), vm.stateFlow.value.activeRooms)
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
        walletJustCreatedInitial: Boolean = false,
    ) : ChipsRepository {
        val balance = MutableStateFlow(initial)
        override val walletJustCreated = MutableStateFlow(walletJustCreatedInitial)
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
        private val activeRooms = MutableStateFlow<List<Room>>(emptyList())

        fun emitActiveRooms(rooms: List<Room>) { activeRooms.value = rooms }

        override fun observeActiveRooms(): Flow<List<Room>> = activeRooms

        override suspend fun createRoom(maxSeats: Int?): CreateRoomOutcome =
            CreateRoomOutcome.NetworkError(RuntimeException("not used"))
        override suspend fun joinRoom(code: String): JoinRoomOutcome =
            JoinRoomOutcome.NetworkError(RuntimeException("not used"))
        override suspend fun leaveRoom(code: String): LeaveRoomOutcome {
            leaveCalls += code
            // Yield before mutating the observed flow so the StateFlow update
            // doesn't re-enter the collector synchronously — mirrors a real
            // network round-trip and keeps the unconfined scheduler honest.
            yield()
            when (leaveOutcome) {
                is LeaveRoomOutcome.Success,
                is LeaveRoomOutcome.NotFound,
                is LeaveRoomOutcome.NotInRoom ->
                    activeRooms.value = activeRooms.value.filterNot { it.code == code }
                is LeaveRoomOutcome.NetworkError,
                is LeaveRoomOutcome.Unknown -> Unit
            }
            return leaveOutcome
        }
        override suspend fun getActiveRooms(): GetActiveRoomsOutcome {
            getActiveRoomsCalls += 1
            yield()
            if (activeRoomsOutcome is GetActiveRoomsOutcome.Success) {
                activeRooms.value = activeRoomsOutcome.rooms
            }
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
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
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
            featuredBadgeIds: List<String>?,
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
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
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
