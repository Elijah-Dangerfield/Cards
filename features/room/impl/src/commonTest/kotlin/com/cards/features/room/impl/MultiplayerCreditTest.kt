package com.dangerfield.cards.features.room.impl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the table-composition gate for multiplayer credit (product-spec.md
 * §5.4): a hand earns full MP credit only on a majority-human table (≥2
 * humans AND humans ≥ bots). The cross-product below mirrors the spec's
 * worked examples so a regression in the rule surfaces against the same
 * cases the product doc reasons about.
 *
 * NOT covered here: the downstream effect of the gate — the XP-mode
 * downgrade is pinned in `HandResultSummaryBuilderTest`, the table label in
 * `RemotePokerSessionFactoryTest`.
 */
class MultiplayerCreditTest {

    @Test
    fun qualifies_fourHumansNoBots_full() {
        assertTrue(MultiplayerCredit.qualifies(table(humans = 4, bots = 0)))
    }

    @Test
    fun qualifies_threeHumansThreeBots_boundaryCounts() {
        assertTrue(MultiplayerCredit.qualifies(table(humans = 3, bots = 3)))
    }

    @Test
    fun qualifies_twoHumansTwoBots_shortHandedParity() {
        assertTrue(MultiplayerCredit.qualifies(table(humans = 2, bots = 2)))
    }

    @Test
    fun qualifies_twoHumansFourBots_botsOutnumber_practiceOnly() {
        assertFalse(MultiplayerCredit.qualifies(table(humans = 2, bots = 4)))
    }

    @Test
    fun qualifies_oneHumanFiveBots_soloRouted() {
        assertFalse(MultiplayerCredit.qualifies(table(humans = 1, bots = 5)))
    }

    @Test
    fun qualifies_twoHumansAlone_emptySeatsIgnored() {
        // Six-seat room, two humans, four empty seats: still qualifies — the
        // gate counts occupied seats only, not the table capacity.
        assertTrue(MultiplayerCredit.qualifies(table(humans = 2, bots = 0, empty = 4)))
    }

    @Test
    fun showsPracticeTierLabel_botStacked_true() {
        assertTrue(MultiplayerCredit.showsPracticeTierLabel(table(humans = 2, bots = 4)))
    }

    @Test
    fun showsPracticeTierLabel_majorityHuman_false() {
        assertFalse(MultiplayerCredit.showsPracticeTierLabel(table(humans = 2, bots = 2)))
    }

    @Test
    fun showsPracticeTierLabel_underFilledButNoBots_false() {
        // One human alone doesn't qualify for MP credit, but with no bots
        // seated the "bots present" copy would be a lie — label stays hidden.
        assertFalse(MultiplayerCredit.showsPracticeTierLabel(table(humans = 1, bots = 0)))
    }

    @Test
    fun isBotsOnly_loneHumanWithBots_true() {
        // The local player is the only human — no human chips on the other
        // side, so the explainer adds the "real chips aren't at stake" line.
        assertTrue(MultiplayerCredit.isBotsOnly(table(humans = 1, bots = 1)))
        assertTrue(MultiplayerCredit.isBotsOnly(table(humans = 1, bots = 5)))
    }

    @Test
    fun isBotsOnly_botStackedWithHumanOpponent_false() {
        // 2H + 4B still shows the practice label, but a human opponent IS at
        // the table, so it isn't bots-only.
        assertFalse(MultiplayerCredit.isBotsOnly(table(humans = 2, bots = 4)))
    }

    @Test
    fun isBotsOnly_humanAlone_false() {
        // One human, no bots seated: not bots-only — there are no bots.
        assertFalse(MultiplayerCredit.isBotsOnly(table(humans = 1, bots = 0)))
    }

    private fun table(humans: Int, bots: Int, empty: Int = 0) = stubGameState(
        seats = buildList {
            repeat(humans) { i -> add(testSeat(index = size, isBot = false, playerId = "human-$i")) }
            repeat(bots) { i -> add(testSeat(index = size, isBot = true, playerId = "bot-$i")) }
            repeat(empty) { add(testSeat(index = size, playerId = null)) }
        },
    )
}
