package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.data.OrphanCandidateVerifier.SkipReason
import com.dangerfield.cards.server.domain.AcquisitionSource
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the shared never-delete-progress guards both orphan sweeps run
 * through (`docs/wiki/account-lifecycle.md` "Hard guards").
 */
class OrphanCandidateVerifierTest {

    private val user = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))

    @Test
    fun passes_freshStarterOnlyCandidate() = runTest {
        val verifier = OrphanSweepFakes.verifier(
            owned = mapOf(user to OrphanSweepFakes.starterRows() + OrphanSweepFakes.foundingMemberRow()),
        )
        assertNull(verifier.skipReason(user))
    }

    @Test
    fun passes_candidateWithNoRowsAtAll() = runTest {
        assertNull(OrphanSweepFakes.verifier().skipReason(user))
    }

    @Test
    fun skips_candidateWithIapSpend() = runTest {
        val verifier = OrphanSweepFakes.verifier(iapSpenders = setOf(user))
        assertEquals(SkipReason.IapSpend, verifier.skipReason(user))
    }

    @Test
    fun skips_candidateWithEngagementInventory() = runTest {
        val verifier = OrphanSweepFakes.verifier(
            owned = mapOf(
                user to OrphanSweepFakes.starterRows() +
                    OrphanSweepFakes.ownedRow("felt_blue_velvet", AcquisitionSource.Purchased),
            ),
        )
        assertEquals(SkipReason.EngagementInventory, verifier.skipReason(user))
    }

    @Test
    fun skips_candidateAtLevel2Threshold() = runTest {
        val verifier = OrphanSweepFakes.verifier(xpByUser = mapOf(user to 100L))
        assertEquals(SkipReason.MeaningfulXp, verifier.skipReason(user))
    }

    @Test
    fun passes_candidateJustBelowLevel2Threshold() = runTest {
        val verifier = OrphanSweepFakes.verifier(xpByUser = mapOf(user to 99L))
        assertNull(verifier.skipReason(user))
    }

    @Test
    fun skips_candidateSeatedInActiveRoom() = runTest {
        val verifier = OrphanSweepFakes.verifier(seatedUsers = setOf(user))
        assertEquals(SkipReason.ActiveRoomSeat, verifier.skipReason(user))
    }
}
