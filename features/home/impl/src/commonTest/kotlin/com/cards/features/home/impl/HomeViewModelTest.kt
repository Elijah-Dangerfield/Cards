package com.dangerfield.cards.features.home.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.User
import com.dangerfield.cards.libraries.cards.UserRepository
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [HomeViewModel]'s init-time fan-in. HomeViewModel is the app
 * entry point's surface — three repositories (user, progression, chips)
 * are subscribed at construction and their emissions hydrate the home
 * state. Pinning here protects against silent regressions in:
 *  - chip / xp updates not reaching the home badge after a hand,
 *  - anonymous flag flipping when the user claims their account,
 *  - user name update propagating after edit-profile.
 */
class HomeViewModelTest : CoroutineTest() {

    @Test
    fun init_loadsUser_andSurfacesNameAndAnonFlag() = runUnitTest {
        val users = FakeUserRepository(initial = anonymousUser(name = "QuietAce72"))
        val vm = buildVm(users = users)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.userName == null) last = awaitItem()
            assertEquals("QuietAce72", last.userName)
            assertEquals(true, last.isAnonymous)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun init_nullUser_yieldsAnonymousTrue_andNullName() = runUnitTest {
        // The home screen renders well in a "no user yet" state — the
        // anon flag must default to true so we don't accidentally show
        // claimed-only UI on the very first frame.
        val vm = buildVm(users = FakeUserRepository(initial = null))
        // Give the init's launch a tick to run the load.
        vm.stateFlow.test {
            // Either the initial state OR the loaded-null state are fine —
            // both must have name=null + anon=true.
            val s = awaitItem()
            assertNull(s.userName)
            assertEquals(true, s.isAnonymous)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun claimedUser_isAnonymousIsFalse() = runUnitTest {
        val users = FakeUserRepository(initial = claimedUser(name = "Real Name"))
        val vm = buildVm(users = users)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.userName == null) last = awaitItem()
            assertEquals(false, last.isAnonymous)
            assertEquals("Real Name", last.userName)
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
            while (last.xp != 250L) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        progression.progression.value = progression.progression.value.copy(totalXp = 1_750L)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.xp != 1_750L) last = awaitItem()
            assertEquals(1_750L, last.xp)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refresh_reReadsUserSnapshot() = runUnitTest {
        // The home pull-to-refresh re-reads `getUser()` (not the
        // observed flow) so a server-side patch via PATCH /v1/me that
        // hasn't fanned back through the cache yet still updates the
        // user label. Pin that the action calls into the repo.
        val users = FakeUserRepository(initial = anonymousUser(name = "OldName"))
        val vm = buildVm(users = users)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.userName == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        users.user.value = users.user.value?.copy(name = "NewName")
        vm.takeAction(HomeAction.Refresh)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.userName != "NewName") last = awaitItem()
            assertTrue(users.getUserCalls >= 2, "Refresh must trigger another getUser() call")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- scaffolding ----------

    private fun buildVm(
        users: FakeUserRepository = FakeUserRepository(initial = anonymousUser()),
        progression: FakeProgressionRepository = FakeProgressionRepository(),
        chips: FakeChipsRepository = FakeChipsRepository(),
    ): HomeViewModel = HomeViewModel(
        userRepository = users,
        progressionRepository = progression,
        chipsRepository = chips,
    )

    private fun anonymousUser(name: String? = "QuietAce72"): User = User(
        name = name,
        createdAt = 1_700_000_000_000,
        lastSessionAt = 1_700_000_000_000,
        hasCompletedOnboarding = true,
        isAnonymous = true,
        sessionsCount = 1,
        appOpenCount = 1,
    )

    private fun claimedUser(name: String? = "RealName"): User =
        anonymousUser(name = name).copy(isAnonymous = false)

    private class FakeUserRepository(initial: User?) : UserRepository {
        val user = MutableStateFlow(initial)
        var getUserCalls: Int = 0
            private set

        override suspend fun ensureUserExists() { /* not used */ }
        override fun observeUser(): Flow<User?> = user
        override suspend fun getUser(): User? {
            getUserCalls += 1
            return user.value
        }
        override suspend fun setName(name: String?) {
            user.value = user.value?.copy(name = name)
        }
        override suspend fun onSessionStarted() { /* not used */ }
        override suspend fun onAppOpened() { /* not used */ }
        override suspend fun setOnboardingComplete() { /* not used */ }
        override suspend fun onShakeDetected() { /* not used */ }
        override suspend fun deleteAll() {
            user.value = null
        }
    }

    private class FakeChipsRepository(
        initial: Long = ChipsRepository.STARTING_GRANT,
    ) : ChipsRepository {
        val balance = MutableStateFlow(initial)
        override fun observeBalance(): Flow<Long> = balance
        override suspend fun getBalance(): Long = balance.value
        override suspend fun applyDelta(delta: Long) {
            balance.value = balance.value + delta
        }
        override suspend fun deleteAll() {
            balance.value = ChipsRepository.STARTING_GRANT
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
    }
}
