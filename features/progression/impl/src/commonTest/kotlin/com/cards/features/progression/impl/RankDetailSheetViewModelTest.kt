package com.dangerfield.cards.features.progression.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [RankDetailSheetViewModel]'s anon ↔ claimed gating. The single
 * load-bearing rule: rank stays at 0 for anonymous users (no MP → no
 * Elo). Claimed users see the placeholder 1200 until the MP layer
 * lands and per-hand Elo deltas start flowing.
 */
class RankDetailSheetViewModelTest : CoroutineTest() {

    @Test
    fun initialState_isAnonymous_andRankIsZero() = runUnitTest {
        val users = FakeUserRepository(initial = null)
        val vm = RankDetailSheetViewModel(userRepository = users)
        assertEquals(true, vm.state.isAnonymous, "null user is treated as anonymous")
        assertEquals(0, vm.state.rank)
    }

    @Test
    fun claimedUser_flipsRankTo1200_placeholder() = runUnitTest {
        val users = FakeUserRepository(initial = claimedUser())
        val vm = RankDetailSheetViewModel(userRepository = users)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.isAnonymous) last = awaitItem()
            assertEquals(false, last.isAnonymous)
            // 1200 is the V1 placeholder until MP/Elo lands. If/when the
            // real rank surfaces, this assertion needs updating in lockstep.
            assertEquals(1200, last.rank)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun userTransition_anonToClaimed_flipsRank() = runUnitTest {
        val users = FakeUserRepository(initial = anonymousUser())
        val vm = RankDetailSheetViewModel(userRepository = users)
        // Initial anon state.
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.rank != 0) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // The user claims their account mid-session — the rank panel
        // should hot-swap to the claimed-user placeholder.
        users.user.value = claimedUser()
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.isAnonymous || last.rank == 0) last = awaitItem()
            assertEquals(false, last.isAnonymous)
            assertEquals(1200, last.rank)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun claimedUser_signingOut_revertsToAnonymous() = runUnitTest {
        // After delete-account or sign-out, the user flow emits null —
        // the VM must collapse rank back to 0 so the screen reads correctly.
        val users = FakeUserRepository(initial = claimedUser())
        val vm = RankDetailSheetViewModel(userRepository = users)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.isAnonymous || last.rank == 0) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        users.user.value = null
        vm.stateFlow.test {
            var last = awaitItem()
            while (!last.isAnonymous || last.rank != 0) last = awaitItem()
            assertEquals(true, last.isAnonymous)
            assertEquals(0, last.rank)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
