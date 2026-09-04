package com.dangerfield.cards.features.home.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AppUpdateSource
import com.dangerfield.cards.libraries.cards.AppVersion
import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.DefaultLevelCurve
import com.dangerfield.cards.libraries.cards.DefaultLevelRewards
import com.dangerfield.cards.libraries.cards.LevelCurve
import com.dangerfield.cards.libraries.cards.LevelReward
import com.dangerfield.cards.libraries.cards.PlayStyleAxes
import com.dangerfield.cards.libraries.cards.PlayStyleHandSummary
import com.dangerfield.cards.libraries.cards.PlayStyleRepository
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionConfig
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.xpAtStartOfLevel
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.core.fixed
import com.dangerfield.cards.libraries.identity.OnboardingStarterGrant
import com.dangerfield.cards.libraries.identity.WelcomeFoundingMemberUntil
import kotlin.time.Clock
import kotlin.time.Instant
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
import com.dangerfield.cards.libraries.social.FriendProfile
import com.dangerfield.cards.libraries.social.FriendRepository
import com.dangerfield.cards.libraries.social.RecentOpponentProfile
import com.dangerfield.cards.libraries.social.RespondToRequestResult
import com.dangerfield.cards.libraries.social.RecentOpponentsRepository
import com.dangerfield.cards.libraries.social.SendFriendRequestResult
import com.dangerfield.cards.libraries.social.SocialEnabled
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun chipsReconciling_propagatesToState() = runUnitTest {
        val chips = FakeChipsRepository(initial = 10_000L)
        val vm = buildVm(chips = chips)
        advanceUntilIdle()
        assertEquals(false, vm.stateFlow.value.chipsReconciling, "idle by default")

        chips.reconciling.value = true
        advanceUntilIdle()
        assertTrue(vm.stateFlow.value.chipsReconciling, "reconcile in flight shows on the header")

        chips.reconciling.value = false
        advanceUntilIdle()
        assertEquals(false, vm.stateFlow.value.chipsReconciling, "settles back to final")
    }

    @Test
    fun resumeAfterAwayChange_armsOdometerReplayFromLastSeen() = runUnitTest {
        // The user last saw 10k; while away they won 1k, so the local source of
        // truth is now 11k. Returning to Home must replay the count-up.
        val chips = FakeChipsRepository(initial = 11_000L)
        val appCache = FakeAppCache(AppData(lastShownChipBalance = 10_000L))
        val vm = buildVm(chips = chips, appCache = appCache)
        advanceUntilIdle()

        vm.takeAction(HomeAction.ScreenResumed)
        advanceUntilIdle()

        val state = vm.stateFlow.value
        assertEquals(10_000L, state.chipsRevealFrom, "odometer rolls from what they last saw")
        assertEquals(11_000L, state.chips, "to the current balance")
        assertTrue(state.chipsRevealKey > 0, "the reveal is armed")
        assertEquals(11_000L, appCache.get().lastShownChipBalance, "baseline advances so it can't replay twice")
    }

    @Test
    fun lateSyncWhileHome_stillReplaysOdometer() = runUnitTest {
        // The leave-game sync responds *after* the user is already on Home: they
        // arrive with the old balance, then the network lands and the local truth
        // updates. The change must still animate, not snap in silently.
        val chips = FakeChipsRepository(initial = 10_000L)
        val appCache = FakeAppCache(AppData(lastShownChipBalance = 10_000L))
        val vm = buildVm(chips = chips, appCache = appCache)
        advanceUntilIdle()

        vm.takeAction(HomeAction.ScreenResumed) // foregrounded; balance still old
        advanceUntilIdle()
        assertEquals(0, vm.stateFlow.value.chipsRevealKey, "nothing changed on arrival")

        // Sync lands late — the local balance updates while Home is foregrounded.
        chips.balance.value = 11_000L
        advanceUntilIdle()

        val state = vm.stateFlow.value
        assertEquals(10_000L, state.chipsRevealFrom, "rolls from what they last saw")
        assertEquals(11_000L, state.chips)
        assertTrue(state.chipsRevealKey > 0, "the late-landing change still animates")
    }

    @Test
    fun resumeWithNoChange_doesNotArmReplay() = runUnitTest {
        val chips = FakeChipsRepository(initial = 10_000L)
        val appCache = FakeAppCache(AppData(lastShownChipBalance = 10_000L))
        val vm = buildVm(chips = chips, appCache = appCache)
        advanceUntilIdle()

        vm.takeAction(HomeAction.ScreenResumed)
        advanceUntilIdle()

        assertEquals(0, vm.stateFlow.value.chipsRevealKey, "nothing changed — no replay")
        assertNull(vm.stateFlow.value.chipsRevealFrom)
    }

    @Test
    fun firstResume_nullBaseline_recordsSilentlyWithoutReplay() = runUnitTest {
        val chips = FakeChipsRepository(initial = 10_000L)
        val appCache = FakeAppCache(AppData(lastShownChipBalance = null))
        val vm = buildVm(chips = chips, appCache = appCache)
        advanceUntilIdle()

        vm.takeAction(HomeAction.ScreenResumed)
        advanceUntilIdle()

        assertEquals(0, vm.stateFlow.value.chipsRevealKey, "no replay before there's a baseline to compare")
        assertEquals(10_000L, appCache.get().lastShownChipBalance, "the current balance becomes the baseline")
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
    fun levelUp_crossingWatermark_showsCelebration_thenMarkShownAdvances() = runUnitTest {
        val progression = FakeProgressionRepository(initial = Progression.Empty)
        val appCache = FakeAppCache()
        val vm = buildVm(progression = progression, appCache = appCache)
        // A blocking notification only presents once Home is settled (PROG-5).
        vm.takeAction(HomeAction.ScreenResumed)
        vm.stateFlow.test {
            var last = awaitItem()
            // Seed settles to level 1.
            while (appCache.get().lastCelebratedLevel != 1) last = awaitItem()

            // Earn enough XP to reach level 2 → celebration surfaces.
            progression.progression.value = progression.progression.value.copy(totalXp = 150L)
            while (last.levelUpCelebration == null) last = awaitItem()
            assertEquals(2, last.levelUpCelebration)

            // Marking it shown (fired by the entry point at navigate time)
            // advances the watermark and clears the trigger.
            vm.takeAction(HomeAction.MarkLevelUpShown)
            while (last.levelUpCelebration != null) last = awaitItem()
            assertEquals(2, appCache.get().lastCelebratedLevel)
            assertTrue(last.levelUpRewards.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun levelUp_staleSnapshotAfterMarkShown_doesNotRefireAlreadyShownLevel() = runUnitTest {
        // PROG-8 / CARDS-7N: the celebration played twice. Mark-shown clears the
        // trigger and advances the watermark through an async appCache write; a
        // snapshot computed just before that write lands still carries the OLD
        // watermark and re-derives the same LevelUp. With the trigger already
        // cleared the old guard let it fire again. The in-memory latch must
        // suppress it, so the next celebration the user sees is only a genuinely
        // new level.
        val progression = FakeProgressionRepository(initial = Progression.Empty)
        val appCache = FakeAppCache()
        val vm = buildVm(progression = progression, appCache = appCache)
        vm.takeAction(HomeAction.ScreenResumed)
        vm.stateFlow.test {
            var last = awaitItem()
            while (appCache.get().lastCelebratedLevel != 1) last = awaitItem()

            // Cross to level 2, see it, and mark it shown (watermark → 2).
            progression.progression.value =
                progression.progression.value.copy(totalXp = xpAtStartOfLevel(2))
            while (last.levelUpCelebration == null) last = awaitItem()
            assertEquals(2, last.levelUpCelebration)
            vm.takeAction(HomeAction.MarkLevelUpShown)
            while (last.levelUpCelebration != null) last = awaitItem()
            assertEquals(2, appCache.get().lastCelebratedLevel)

            // A stale snapshot still carrying the pre-mark watermark re-arrives,
            // then the user genuinely reaches level 3. The next (and only)
            // celebration must be level 3 — never a re-fire of the shown level 2.
            appCache.update { it.copy(lastCelebratedLevel = 1) }
            progression.progression.value =
                progression.progression.value.copy(totalXp = xpAtStartOfLevel(3))
            while (last.levelUpCelebration == null) last = awaitItem()
            assertEquals(
                3,
                last.levelUpCelebration,
                "an already-shown level must not re-fire from a stale snapshot (PROG-8)",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun levelUp_gateFirstSeesLeveledUpXp_butWatermarkAnchored_stillCelebrates() = runUnitTest {
        // PROG-3 regression: the celebration was silently dropped when the gate's
        // FIRST progression emission already reflected a level-up earned this
        // session (the gate raced behind the level-up). The granter now anchors
        // lastCelebratedLevel to the pre-session level (here 1) before the hand,
        // so even though Home's gate first observes level 2, the crossing 1 -> 2
        // surfaces the celebration instead of being swallowed by the unset seed.
        val progression = FakeProgressionRepository(initial = Progression.Empty.copy(totalXp = 150L))
        val appCache = FakeAppCache(initial = AppData(lastCelebratedLevel = 1))
        val vm = buildVm(progression = progression, appCache = appCache)
        vm.takeAction(HomeAction.ScreenResumed)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.levelUpCelebration == null) last = awaitItem()
            assertEquals(2, last.levelUpCelebration)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun levelUp_crossingIntoRewardedLevel_revealsAggregatedRewards() = runUnitTest {
        // Level 3 grants 1,000 chips (DefaultLevelRewards). Starting from a fresh
        // level-1 account, the watermark seeds to 1, then a jump straight to
        // level 3 crosses levels 2 (no reward) + 3 (chips) — the reveal
        // aggregates that to a single chip row.
        val progression = FakeProgressionRepository(initial = Progression.Empty)
        val appCache = FakeAppCache()
        val vm = buildVm(progression = progression, appCache = appCache)
        vm.takeAction(HomeAction.ScreenResumed)
        vm.stateFlow.test {
            var last = awaitItem()
            while (appCache.get().lastCelebratedLevel != 1) last = awaitItem()

            progression.progression.value =
                progression.progression.value.copy(totalXp = xpAtStartOfLevel(3))
            while (last.levelUpCelebration == null) last = awaitItem()
            assertEquals(3, last.levelUpCelebration)
            assertEquals(listOf(LevelReward.Chips(1_000)), last.levelUpRewards)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun levelUp_crossingWhileHomeNotSettled_isNotConsumed_firesWhenHomeSettles() = runUnitTest {
        // PROG-5: the cited missed-celebration. A level crossing that resolves
        // while Home isn't the settled/foreground screen must NOT be silently
        // consumed — it has to fire once Home settles. Here we never call
        // ScreenResumed until after the crossing has landed.
        val progression = FakeProgressionRepository(initial = Progression.Empty)
        val appCache = FakeAppCache(initial = AppData(lastCelebratedLevel = 1))
        val vm = buildVm(progression = progression, appCache = appCache)

        // Crossing lands while Home is NOT settled — nothing should present yet.
        progression.progression.value = progression.progression.value.copy(totalXp = 150L)
        advanceUntilIdle()
        assertNull(
            vm.stateFlow.value.levelUpCelebration,
            "a crossing while Home isn't settled must not present yet",
        )

        // Home settles — the pending crossing now fires exactly once.
        vm.takeAction(HomeAction.ScreenResumed)
        advanceUntilIdle()
        assertEquals(2, vm.stateFlow.value.levelUpCelebration, "pending crossing fires on settle")
    }

    @Test
    fun playStyleUnlock_crossingThreshold_firesOnceOnSettledHome_thenMarksSeen() = runUnitTest {
        // PROG-6: crossing the play-style sample threshold surfaces a one-shot
        // unlock celebration, gated on a settled Home, and marks the watermark
        // only after the present.
        val playStyle = FakePlayStyleRepository(initial = null)
        val appCache = FakeAppCache()
        val vm = buildVm(playStyle = playStyle, appCache = appCache)
        vm.takeAction(HomeAction.ScreenResumed)

        vm.eventFlow.test {
            expectNoEvents()
            // Enough sampled hands land to cross MIN_SAMPLE.
            playStyle.style.value = PlayStyleAxes.Empty.copy(sampleSize = PlayStyleAxes.MIN_SAMPLE)
            val event = awaitItem()
            assertTrue(event is HomeEvent.OpenPlayStyleUnlocked)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(
            appCache.get().playStyleUnlockSeen,
            "the unlock watermark is marked only after the celebration presents",
        )
    }

    @Test
    fun playStyleUnlock_alreadySeen_doesNotFire() = runUnitTest {
        val playStyle = FakePlayStyleRepository(
            initial = PlayStyleAxes.Empty.copy(sampleSize = 40),
        )
        val appCache = FakeAppCache(initial = AppData(playStyleUnlockSeen = true))
        val vm = buildVm(playStyle = playStyle, appCache = appCache)
        vm.takeAction(HomeAction.ScreenResumed)

        vm.eventFlow.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun mpAchievements_pendingQueue_presentsOnSettle_thenDrainsOnDismiss() = runUnitTest {
        // PROG-13: unlocks earned in a real-chip MP game queue in AppData; Home
        // presents them once settled and drains the queue on dismiss so they
        // never replay.
        val appCache = FakeAppCache(AppData(pendingHomeAchievementIds = listOf("HANDS_10")))
        val vm = buildVm(appCache = appCache)
        vm.takeAction(HomeAction.ScreenResumed)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.achievementCelebration.isEmpty()) last = awaitItem()
            assertEquals(
                listOf(AchievementId.HANDS_10),
                last.achievementCelebration.map { it.achievement.id },
                "the queued unlock is reconstructed and presented",
            )

            vm.takeAction(HomeAction.MarkAchievementCelebrationShown)
            while (last.achievementCelebration.isNotEmpty()) last = awaitItem()
            assertTrue(
                appCache.get().pendingHomeAchievementIds.isEmpty(),
                "dismissing drains the persisted queue so it can't replay",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun mpAchievements_notPresentedUntilHomeSettles() = runUnitTest {
        // Same settled-Home discipline as the level-up celebration (PROG-5): a
        // queue present on a not-yet-settled Home must wait for the resume.
        val appCache = FakeAppCache(AppData(pendingHomeAchievementIds = listOf("HANDS_10")))
        val vm = buildVm(appCache = appCache)
        advanceUntilIdle()
        assertTrue(
            vm.stateFlow.value.achievementCelebration.isEmpty(),
            "nothing presents before Home is settled",
        )

        vm.takeAction(HomeAction.ScreenResumed)
        advanceUntilIdle()
        assertEquals(
            listOf(AchievementId.HANDS_10),
            vm.stateFlow.value.achievementCelebration.map { it.achievement.id },
            "the pending celebration fires on settle",
        )
    }

    @Test
    fun activeRooms_success_populatesState() = runUnitTest {
        val room = sampleRoom(code = "WXYZ12")
        val rooms = FakeRoomRepository(
            activeRoomsOutcome = GetActiveRoomsOutcome.Success(listOf(room)),
        )
        // Active rooms only fetch once a real session exists (gated on
        // Profile.Authenticated), so the user must be authenticated.
        val profile = FakeProfileRepository(
            initial = authenticatedProfile(displayName = "Seated", isAnonymous = true),
        )
        val vm = buildVm(rooms = rooms, profile = profile)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.activeRooms.isEmpty()) last = awaitItem()
            assertEquals(listOf(ActiveRoomSummary(code = "WXYZ12")), last.activeRooms)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun activeRooms_notFetched_whileSessionless_thenFetchesWhenAuthArrives() = runUnitTest {
        // The 401-on-init fix: a session-less (Fallback) profile must not fetch
        // /v1/me/active-rooms; the fetch fires once self-heal / sign-in flips the
        // profile to Authenticated.
        val rooms = FakeRoomRepository(
            activeRoomsOutcome = GetActiveRoomsOutcome.Success(emptyList()),
        )
        val profile = FakeProfileRepository(initial = Profile.Fallback(id = "anon"))
        buildVm(rooms = rooms, profile = profile)
        assertEquals(0, rooms.getActiveRoomsCalls, "no fetch while session-less")

        profile.emit(authenticatedProfile(displayName = "Healed", isAnonymous = true))
        assertEquals(1, rooms.getActiveRoomsCalls, "fetch once a real session arrives")
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
        val vm = buildVm(rooms = rooms, profile = seatedProfile())

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
        val vm = buildVm(rooms = rooms, profile = seatedProfile())
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
    fun welcomeGate_accountJustCreated_waitsForChips_thenFires() = runUnitTest {
        // accountJustCreated=true + Profile.Authenticated, but chips not yet
        // hydrated and no starter-grant config → fall back to the balance, so the
        // gate waits for the balance before firing (reveals the real number).
        val profile = FakeProfileRepository(
            initial = authenticatedProfile(displayName = "FreshInstall", isAnonymous = true),
            accountJustCreatedInitial = true,
        )
        val chips = FakeChipsRepository(initial = null)
        val appCache = FakeAppCache() // didSeeInitialGrantInOnboarding = false
        val vm = buildVm(profile = profile, chips = chips, appCache = appCache)
        // A blocking notification only presents once Home is settled (PROG-5).
        vm.takeAction(HomeAction.ScreenResumed)

        vm.eventFlow.test {
            expectNoEvents()
            // Wallet sync lands with the authoritative balance.
            chips.balance.value = 10_500L
            val event = awaitItem()
            assertTrue(event is HomeEvent.OpenWelcomeDialog)
            assertEquals(10_500L, event.payload.grantChips)
            assertEquals("FreshInstall", event.payload.displayName)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            true, appCache.get().welcomeSeen,
            "gate must mark the dialog seen at emit time so it doesn't re-fire",
        )
    }

    @Test
    fun welcomeGate_accountNotJustCreated_doesNotFire() = runUnitTest {
        // Returning user / switched-into account: accountJustCreated=false →
        // never fire, even with a hydrated balance. This is the leak fix —
        // the signal is authoritative + server-sourced, false for a pre-existing
        // account (the AUTH-23 bug where an established account saw the welcome).
        val profile = FakeProfileRepository(
            initial = authenticatedProfile(displayName = "Returning", isAnonymous = false),
        )
        val chips = FakeChipsRepository(initial = 250_000L)
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
            accountJustCreatedInitial = true,
        )
        val chips = FakeChipsRepository(initial = 10_000L)
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
        val profile = FakeProfileRepository(
            initial = Profile.Fallback(id = "anon"),
            accountJustCreatedInitial = true,
        )
        val chips = FakeChipsRepository(initial = 10_000L)
        val appCache = FakeAppCache()
        val vm = buildVm(profile = profile, chips = chips, appCache = appCache)
        vm.takeAction(HomeAction.ScreenResumed)

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
    fun welcomeGate_foundingWindowOpen_firesForExistingPlayer_withNoRevealAndMarksSeen() = runUnitTest {
        // Not a fresh account, but the founding window is open (config close-time
        // 10_000 > the fixed clock's now=0) → thank the existing early player once,
        // founding copy only, no starter-grant reveal.
        val profile = FakeProfileRepository(
            initial = authenticatedProfile(displayName = "EarlyBird", isAnonymous = false),
        )
        val chips = FakeChipsRepository(initial = 132_250L)
        val appCache = FakeAppCache()
        val vm = buildVm(
            profile = profile,
            chips = chips,
            appCache = appCache,
            foundingMemberUntil = WelcomeFoundingMemberUntil.forTest(untilEpochMs = 10_000L),
        )
        vm.takeAction(HomeAction.ScreenResumed)

        vm.eventFlow.test {
            val event = awaitItem()
            assertTrue(event is HomeEvent.OpenWelcomeDialog)
            assertTrue(event.payload.isFounding)
            assertNull(event.payload.grantChips)
            assertFalse(event.payload.grantPending)
            assertEquals("EarlyBird", event.payload.displayName)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            true, appCache.get().welcomeSeen,
            "founding welcome must mark seen so an early player is thanked once",
        )
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
        val vm = buildVm(rooms = rooms, profile = seatedProfile())
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.activeRooms.isEmpty()) last = awaitItem()

            vm.takeAction(HomeAction.Forfeit(code = "AAA111"))
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(ActiveRoomSummary("AAA111")), vm.stateFlow.value.activeRooms)
        assertEquals(listOf("AAA111"), rooms.leaveCalls)
    }

    @Test
    fun forfeit_networkError_surfacesFailureEvent() = runUnitTest {
        // ROOM-17: the user confirmed a destructive Forfeit dialog — a leave
        // that fails over the wire must say so, not keep the banner in silence.
        val rooms = FakeRoomRepository(
            activeRoomsOutcome = GetActiveRoomsOutcome.Success(listOf(sampleRoom(code = "AAA111"))),
            leaveOutcome = LeaveRoomOutcome.NetworkError(RuntimeException("boom")),
        )
        val vm = buildVm(rooms = rooms, profile = seatedProfile())
        advanceUntilIdle()

        vm.eventFlow.test {
            vm.takeAction(HomeAction.Forfeit(code = "AAA111"))
            assertTrue(awaitItem() is HomeEvent.ForfeitFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun forfeit_unknownError_surfacesFailureEvent() = runUnitTest {
        // A server rejection also leaves the seat held — same feedback path.
        val rooms = FakeRoomRepository(
            activeRoomsOutcome = GetActiveRoomsOutcome.Success(listOf(sampleRoom(code = "AAA111"))),
            leaveOutcome = LeaveRoomOutcome.Unknown(RuntimeException("boom")),
        )
        val vm = buildVm(rooms = rooms, profile = seatedProfile())
        advanceUntilIdle()

        vm.eventFlow.test {
            vm.takeAction(HomeAction.Forfeit(code = "AAA111"))
            assertTrue(awaitItem() is HomeEvent.ForfeitFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun forfeit_success_emitsNoFailureEvent() = runUnitTest {
        val rooms = FakeRoomRepository(
            activeRoomsOutcome = GetActiveRoomsOutcome.Success(listOf(sampleRoom(code = "AAA111"))),
        )
        val vm = buildVm(rooms = rooms, profile = seatedProfile())
        advanceUntilIdle()

        vm.eventFlow.test {
            vm.takeAction(HomeAction.Forfeit(code = "AAA111"))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(vm.stateFlow.value.activeRooms.isEmpty(), "banner clears via the observed rooms flow")
    }

    @Test
    fun recentOpponents_refreshOnAuth_resolvesIntoState() = runUnitTest {
        // The shelf refreshes once a real session lands; the resolved profiles
        // surface as RecentOpponent tiles (none "Sent" yet).
        val recents = FakeRecentOpponentsRepository(
            onRefresh = listOf(
                recentProfile("u1", "Patrice", "🦁", "#C658E4"),
                recentProfile("u2", "Jules", "🐙", "#58C0E4"),
            ),
        )
        val profile = FakeProfileRepository(
            initial = authenticatedProfile(displayName = "Seated", isAnonymous = true),
        )
        val vm = buildVm(profile = profile, recentOpponents = recents)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.recentOpponents.isEmpty()) last = awaitItem()
            assertEquals(listOf("u1", "u2"), last.recentOpponents.map { it.id })
            assertEquals("Patrice", last.recentOpponents.first().displayName)
            assertTrue(last.recentOpponents.none { it.requestSent })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun addFriend_optimisticallyFlipsSent_andSendsRequest() = runUnitTest {
        val recents = FakeRecentOpponentsRepository(onRefresh = listOf(recentProfile("u1")))
        val friends = FakeFriendRepository(result = SendFriendRequestResult.Requested)
        val vm = buildVm(profile = seatedProfile(), recentOpponents = recents, friends = friends)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.recentOpponents.isEmpty()) last = awaitItem()

            vm.takeAction(HomeAction.AddFriend("u1"))
            while (last.recentOpponents.none { it.requestSent }) last = awaitItem()
            assertTrue(last.recentOpponents.single { it.id == "u1" }.requestSent)
            assertEquals(listOf("u1"), friends.sentTo)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun pendingFriendRequests_reflectInboxCount_onRealSession() = runUnitTest {
        // A claimed (non-anonymous) session triggers an inbox refresh; the
        // resolved count surfaces on state for the Friends strip badge.
        val friends = FakeFriendRepository(
            onRefresh = listOf(friendProfile("u1"), friendProfile("u2")),
        )
        val profile = FakeProfileRepository(
            initial = authenticatedProfile(displayName = "Claimed", isAnonymous = false),
        )
        val vm = buildVm(profile = profile, friends = friends)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.pendingFriendRequests != 2) last = awaitItem()
            assertEquals(2, last.pendingFriendRequests)
            assertEquals(1, friends.refreshCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun socialDisabled_skipsInboxRefresh_andGatesStateOff() = runUnitTest {
        // SOC-2: with social.enabled off, the Home social shelves are hidden, so
        // the VM must neither subscribe to the friend graph nor fire the inbox
        // refresh — even for a claimed account that would otherwise trigger it.
        val friends = FakeFriendRepository(
            onRefresh = listOf(friendProfile("u1"), friendProfile("u2")),
        )
        val profile = FakeProfileRepository(
            initial = authenticatedProfile(displayName = "Claimed", isAnonymous = false),
        )
        val vm = buildVm(profile = profile, friends = friends, socialEnabled = false)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.userName == null) last = awaitItem()
            yield()
            assertEquals(false, last.socialEnabled)
            assertEquals(0, friends.refreshCalls)
            assertEquals(0, last.pendingFriendRequests)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun pendingFriendRequests_skipRefreshForAnonymousSession() = runUnitTest {
        // A guest has no friend graph — the inbox refresh must not fire, so the
        // badge stays at zero.
        val friends = FakeFriendRepository(onRefresh = listOf(friendProfile("u1")))
        val vm = buildVm(profile = seatedProfile(), friends = friends)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.userName == null) last = awaitItem()
            yield()
            assertEquals(0, friends.refreshCalls)
            assertEquals(0, last.pendingFriendRequests)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun addFriend_rejectedRequest_revertsSent() = runUnitTest {
        // A 403 (not played with) comes back — the optimistic flip is undone so
        // the tile returns to "Add".
        val recents = FakeRecentOpponentsRepository(onRefresh = listOf(recentProfile("u1")))
        val friends = FakeFriendRepository(result = SendFriendRequestResult.NotPlayedWith)
        val vm = buildVm(profile = seatedProfile(), recentOpponents = recents, friends = friends)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.recentOpponents.isEmpty()) last = awaitItem()

            vm.takeAction(HomeAction.AddFriend("u1"))
            // Flips to Sent optimistically...
            while (last.recentOpponents.none { it.requestSent }) last = awaitItem()
            // ...then reverts once the rejection lands.
            while (last.recentOpponents.any { it.requestSent }) last = awaitItem()
            assertTrue(last.recentOpponents.single { it.id == "u1" }.requestSent.not())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun addFriend_sentFlagSurvivesRecentOpponentsRefresh() = runUnitTest {
        // A re-resolve of the shelf must preserve the "Sent" state for an id the
        // user already requested this session.
        val recents = FakeRecentOpponentsRepository(onRefresh = listOf(recentProfile("u1")))
        val friends = FakeFriendRepository(result = SendFriendRequestResult.Requested)
        val vm = buildVm(profile = seatedProfile(), recentOpponents = recents, friends = friends)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.recentOpponents.isEmpty()) last = awaitItem()
            vm.takeAction(HomeAction.AddFriend("u1"))
            while (last.recentOpponents.none { it.requestSent }) last = awaitItem()

            recents.emit(listOf(recentProfile("u1"), recentProfile("u2")))
            while (last.recentOpponents.size != 2) last = awaitItem()
            assertTrue(last.recentOpponents.single { it.id == "u1" }.requestSent)
            assertTrue(last.recentOpponents.single { it.id == "u2" }.requestSent.not())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- scaffolding ----------

    private fun buildVm(
        progression: FakeProgressionRepository = FakeProgressionRepository(),
        achievements: FakeAchievementRepository = FakeAchievementRepository(),
        chips: FakeChipsRepository = FakeChipsRepository(),
        rooms: FakeRoomRepository = FakeRoomRepository(),
        profile: FakeProfileRepository = FakeProfileRepository(),
        recentOpponents: FakeRecentOpponentsRepository = FakeRecentOpponentsRepository(),
        friends: FakeFriendRepository = FakeFriendRepository(),
        playStyle: FakePlayStyleRepository = FakePlayStyleRepository(),
        appCache: FakeAppCache = FakeAppCache(),
        progressionConfig: ProgressionConfig = FakeProgressionConfig(),
        // Empty config → the starter-grant amount is unknown, so the welcome
        // falls back to the fresh account's balance (what these tests assert).
        onboardingStarterGrant: OnboardingStarterGrant = OnboardingStarterGrant(
            object : AppConfigMap() { override val map = emptyMap<String, Any>() },
        ),
        // Default: no founding window (config sentinel 0), so the welcome wears its
        // plain new-account copy unless a test opts into the window.
        foundingMemberUntil: WelcomeFoundingMemberUntil = WelcomeFoundingMemberUntil(
            object : AppConfigMap() { override val map = emptyMap<String, Any>() },
        ),
        clock: Clock = Clock.fixed(Instant.fromEpochMilliseconds(0)),
        socialEnabled: Boolean = true,
    ): HomeViewModel = HomeViewModel(
        progressionRepository = progression,
        achievementRepository = achievements,
        chipsRepository = chips,
        roomRepository = rooms,
        profileRepository = profile,
        recentOpponentsRepository = recentOpponents,
        friendRepository = friends,
        playStyleRepository = playStyle,
        progressionConfig = progressionConfig,
        onboardingStarterGrant = onboardingStarterGrant,
        foundingMemberUntil = foundingMemberUntil,
        clock = clock,
        appCache = appCache,
        appScope = AppCoroutineScope(dispatchers),
        appUpdateSource = object : AppUpdateSource {
            override suspend fun latestAvailableVersion(): AppVersion? = null
        },
        socialEnabledConfig = SocialEnabled.forTest(socialEnabled),
    )

    private fun recentProfile(
        id: String,
        displayName: String = "name-$id",
        avatarEmoji: String = "🦊",
        avatarBackgroundColorHex: String? = null,
    ): RecentOpponentProfile = RecentOpponentProfile(
        id = id,
        displayName = displayName,
        avatarEmoji = avatarEmoji,
        avatarBackgroundColorHex = avatarBackgroundColorHex,
    )

    private fun friendProfile(
        id: String,
        displayName: String = "name-$id",
        avatarEmoji: String = "🦊",
        avatarBackgroundColorHex: String? = null,
    ): FriendProfile = FriendProfile(
        id = id,
        displayName = displayName,
        avatarEmoji = avatarEmoji,
        avatarBackgroundColorHex = avatarBackgroundColorHex,
    )

    private class FakeRecentOpponentsRepository(
        private val onRefresh: List<RecentOpponentProfile> = emptyList(),
    ) : RecentOpponentsRepository {
        private val recent = MutableStateFlow<List<RecentOpponentProfile>>(emptyList())
        var refreshCalls: Int = 0
            private set
        fun emit(profiles: List<RecentOpponentProfile>) { recent.value = profiles }
        override fun observe(): Flow<List<RecentOpponentProfile>> = recent
        override suspend fun refresh(limit: Int) {
            refreshCalls += 1
            recent.value = onRefresh
        }
    }

    private class FakeFriendRepository(
        private val result: SendFriendRequestResult = SendFriendRequestResult.Requested,
        private val onRefresh: List<FriendProfile> = emptyList(),
    ) : FriendRepository {
        val sentTo: MutableList<String> = mutableListOf()
        var refreshCalls: Int = 0
            private set
        private val incoming = MutableStateFlow<List<FriendProfile>>(emptyList())

        override suspend fun sendRequest(userId: String): SendFriendRequestResult {
            sentTo += userId
            return result
        }

        override fun observeIncomingRequests(): Flow<List<FriendProfile>> = incoming.asStateFlow()

        override suspend fun refreshIncomingRequests() {
            refreshCalls += 1
            incoming.value = onRefresh
        }

        override suspend fun accept(userId: String): RespondToRequestResult {
            incoming.update { current -> current.filterNot { it.id == userId } }
            return RespondToRequestResult.Ok
        }

        override suspend fun decline(userId: String): RespondToRequestResult {
            incoming.update { current -> current.filterNot { it.id == userId } }
            return RespondToRequestResult.Ok
        }
    }

    private class FakeProgressionConfig : ProgressionConfig {
        override fun rewardsForLevel(level: Int): List<LevelReward> =
            DefaultLevelRewards.rewardsForLevel(level)
        override fun levelCurve(): LevelCurve = DefaultLevelCurve
    }

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

    /** A signed-in profile for tests that exercise active-rooms (now gated on a
     *  real session). */
    private fun seatedProfile(): FakeProfileRepository =
        FakeProfileRepository(initial = authenticatedProfile(displayName = "Seated", isAnonymous = true))

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
        val reconciling = MutableStateFlow(false)
        override val isReconciling = reconciling
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
        private val leaveOutcome: LeaveRoomOutcome = LeaveRoomOutcome.Success(),
    ) : RoomRepository {
        var getActiveRoomsCalls: Int = 0
            private set
        val leaveCalls: MutableList<String> = mutableListOf()
        private val activeRooms = MutableStateFlow<List<Room>>(emptyList())

        fun emitActiveRooms(rooms: List<Room>) { activeRooms.value = rooms }

        override fun observeActiveRooms(): Flow<List<Room>> = activeRooms

        override suspend fun createRoom(
            maxSeats: Int?,
            buyIn: Long?,
            open: Boolean,
            feltProductId: String?,
            cardBackProductId: String?,
        ): CreateRoomOutcome = CreateRoomOutcome.NetworkError(RuntimeException("not used"))
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
        override suspend fun addBot(code: String, seatIndex: Int?): com.dangerfield.cards.libraries.rooms.AddBotOutcome =
            error("unused")
        override suspend fun removeBot(code: String, botUserId: String): com.dangerfield.cards.libraries.rooms.RemoveBotOutcome =
            error("unused")
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
        accountJustCreatedInitial: Boolean = false,
    ) : ProfileRepository {
        val profile = MutableStateFlow(initial)
        /** Drives the authoritative brand-new-account signal the welcome gate reads. */
        val accountJustCreated = MutableStateFlow(accountJustCreatedInitial)
        suspend fun emit(next: Profile) { profile.emit(next) }
        override suspend fun current(): Profile = profile.value
        override fun observe(): Flow<Profile> = profile
        override fun observeAccountJustCreated(): Flow<Boolean> = accountJustCreated
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
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAll() { /* not used */ }
    }

    private class FakePlayStyleRepository(
        initial: PlayStyleAxes? = null,
    ) : PlayStyleRepository {
        val style = MutableStateFlow(initial)
        override fun observeOwnStyle(): Flow<PlayStyleAxes?> = style
        override suspend fun getOwnStyle(): PlayStyleAxes? = style.value
        override suspend fun recordHand(summary: PlayStyleHandSummary) = Unit
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
        override suspend fun getStyleFor(userId: String): Result<PlayStyleAxes?> = Result.success(null)
        override suspend fun deleteAll() { style.value = null }
    }

    private class FakeAppCache(initial: AppData = AppData()) : AppCache {
        private val state = MutableStateFlow(initial)
        override val updates: Flow<AppData> = state
        override suspend fun get(): AppData = state.value
        override suspend fun set(value: AppData) { state.value = value }
        override suspend fun clear() { state.value = AppData() }
    }
}
