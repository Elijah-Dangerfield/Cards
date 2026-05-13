package com.dangerfield.cards.libraries.gameplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PotBuilderTest {

    private fun seat(
        index: Int,
        contributed: Long,
        inHand: Boolean,
    ): Seat = Seat(
        index = index,
        playerId = "p$index",
        displayName = "P$index",
        stack = 0,
        seatStatus = SeatStatus.Active,
        handParticipation = if (inHand) HandParticipation.InHand else HandParticipation.Folded,
        contributedThisHand = contributed,
    )

    @Test
    fun singlePotWhenEveryoneContributedEqually() {
        val pots = PotBuilder.buildPots(
            listOf(
                seat(0, 100, true),
                seat(1, 100, true),
                seat(2, 100, true),
            )
        )
        assertEquals(1, pots.size)
        assertEquals(300L, pots.first().amount)
        assertEquals(listOf(0, 1, 2), pots.first().eligibleSeatIndexes)
    }

    @Test
    fun foldedPlayerStillContributesToMainPot() {
        val pots = PotBuilder.buildPots(
            listOf(
                seat(0, 100, false),
                seat(1, 100, true),
                seat(2, 100, true),
            )
        )
        assertEquals(1, pots.size)
        assertEquals(300L, pots.first().amount)
        assertEquals(listOf(1, 2), pots.first().eligibleSeatIndexes)
    }

    @Test
    fun threeWayAllInBuildsThreeTiers() {
        val pots = PotBuilder.buildPots(
            listOf(
                seat(0, 50, true),
                seat(1, 200, true),
                seat(2, 300, true),
            )
        )
        assertEquals(3, pots.size)
        assertEquals(150L, pots[0].amount)
        assertEquals(listOf(0, 1, 2), pots[0].eligibleSeatIndexes)
        assertEquals(300L, pots[1].amount)
        assertEquals(listOf(1, 2), pots[1].eligibleSeatIndexes)
        assertEquals(100L, pots[2].amount)
        assertEquals(listOf(2), pots[2].eligibleSeatIndexes)
    }

    @Test
    fun samePotIfSameEligibleSet() {
        val pots = PotBuilder.buildPots(
            listOf(
                seat(0, 50, true),
                seat(1, 100, true),
            )
        )
        assertEquals(2, pots.size)
        assertEquals(100L, pots[0].amount)
        assertEquals(listOf(0, 1), pots[0].eligibleSeatIndexes)
        assertEquals(50L, pots[1].amount)
        assertEquals(listOf(1), pots[1].eligibleSeatIndexes)
    }

    @Test
    fun foldedPlayerExtraChipsRoll() {
        val pots = PotBuilder.buildPots(
            listOf(
                seat(0, 400, false),
                seat(1, 100, true),
                seat(2, 100, true),
            )
        )
        val total = pots.sumOf { it.amount }
        assertEquals(600L, total)
        assertTrue(pots.all { it.eligibleSeatIndexes.isNotEmpty() })
    }

    @Test
    fun noContributorsReturnsEmpty() {
        val pots = PotBuilder.buildPots(emptyList())
        assertTrue(pots.isEmpty())
    }
}
