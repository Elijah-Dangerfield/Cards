package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.usecase.PlayerStatHandSummaryBuilder
import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.XpMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [PlayerStatHandSummaryBuilder]: the field-by-field mapping plus the one
 * stateful bit — the order-dependent no-bust streak (increment when the human
 * survives, reset to 0 on bust, seeded from the cached snapshot).
 */
class PlayerStatHandSummaryBuilderTest {

    @Test
    fun mapsOutcomeFields() {
        val out = PlayerStatHandSummaryBuilder().build(
            summary = summary(handId = "3", wonPot = true, reachedShowdown = true),
            context = context(opponentBotNames = listOf("Jane")),
        )

        assertEquals("3", out.handId)
        assertTrue(out.won)
        assertTrue(out.vsBot)
        assertEquals("Jane", out.beatenBotId)
        assertEquals(false, out.lostAtShowdown)
    }

    @Test
    fun lostAtShowdown_whenReachedShowdownAndDidNotWin() {
        val out = PlayerStatHandSummaryBuilder().build(
            summary = summary(reachedShowdown = true, wonPot = false),
            context = context(),
        )

        assertTrue(out.lostAtShowdown)
    }

    @Test
    fun beatenBotId_nullWhenLostOrNoBots() {
        val builder = PlayerStatHandSummaryBuilder()

        val lost = builder.build(summary(wonPot = false), context(listOf("Jane")))
        assertNull(lost.beatenBotId)

        val noBots = builder.build(summary(wonPot = true), context(emptyList()))
        assertNull(noBots.beatenBotId)
        assertEquals(false, noBots.vsBot)
    }

    @Test
    fun streak_incrementsAcrossSurvivedHands_resetsOnBust() {
        val builder = PlayerStatHandSummaryBuilder()

        val h1 = builder.build(summary(handId = "1"), context(humanEndingStack = 100))
        val h2 = builder.build(summary(handId = "2"), context(humanEndingStack = 200))
        val bust = builder.build(summary(handId = "3"), context(humanEndingStack = 0))
        val h4 = builder.build(summary(handId = "4"), context(humanEndingStack = 50))

        assertEquals(1, h1.noBustStreak)
        assertEquals(2, h2.noBustStreak)
        assertEquals(0, bust.noBustStreak)
        assertEquals(1, h4.noBustStreak)
    }

    @Test
    fun streak_seededFromSnapshot_onlyBeforeFirstBuild() {
        val builder = PlayerStatHandSummaryBuilder()
        builder.seedStreak(7)

        val h1 = builder.build(summary(handId = "1"), context(humanEndingStack = 100))
        // A late seed is ignored — the running streak is already authoritative.
        builder.seedStreak(99)
        val h2 = builder.build(summary(handId = "2"), context(humanEndingStack = 100))

        assertEquals(8, h1.noBustStreak)
        assertEquals(9, h2.noBustStreak)
    }

    private fun summary(
        handId: String = "1",
        wonPot: Boolean = false,
        reachedShowdown: Boolean = false,
        wasFold: Boolean = false,
    ) = HandResultSummary(
        handId = handId,
        mode = XpMode.BOTS,
        wasFold = wasFold,
        reachedShowdown = reachedShowdown,
        wonPot = wonPot,
        chipsCommitted = 0,
        bigBlind = 2,
        handCategory = null,
    )

    private fun context(
        opponentBotNames: List<String> = listOf("Jane"),
        humanEndingStack: Long = 100,
    ) = AchievementHandContext(
        opponentBotNames = opponentBotNames,
        botDifficulty = "Casual",
        humanStartingStack = 100,
        humanEndingStack = humanEndingStack,
        bigBlind = 2,
    )
}
