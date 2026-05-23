package com.dangerfield.cards.features.progression.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AuthState
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
        // Before AuthRepository has resolved (no replay yet), the VM
        // should still default to anonymous + rank 0 — that's the safe
        // first-frame state.
        val auth = FakeAuthRepository(initial = null)
        val vm = RankDetailSheetViewModel(authRepository = auth)
        assertEquals(true, vm.state.isAnonymous, "unresolved auth treated as anonymous")
        assertEquals(0, vm.state.rank)
    }

    @Test
    fun claimedUser_flipsRankTo1200_placeholder() = runUnitTest {
        val auth = FakeAuthRepository(initial = claimedAuthState())
        val vm = RankDetailSheetViewModel(authRepository = auth)
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
        val auth = FakeAuthRepository(initial = anonymousAuthState())
        val vm = RankDetailSheetViewModel(authRepository = auth)
        // Initial anon state.
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.rank != 0) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // The user claims their account mid-session — the rank panel
        // should hot-swap to the claimed-user placeholder.
        auth.emit(claimedAuthState())
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
        // After delete-account or sign-out the auth flow emits
        // Unauthenticated — the VM must collapse rank back to 0 so the
        // screen reads correctly.
        val auth = FakeAuthRepository(initial = claimedAuthState())
        val vm = RankDetailSheetViewModel(authRepository = auth)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.isAnonymous || last.rank == 0) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        auth.emit(AuthState.Unauthenticated())
        vm.stateFlow.test {
            var last = awaitItem()
            while (!last.isAnonymous || last.rank != 0) last = awaitItem()
            assertEquals(true, last.isAnonymous)
            assertEquals(0, last.rank)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
