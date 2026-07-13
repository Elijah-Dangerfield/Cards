package com.dangerfield.cards.libraries.bots

import com.dangerfield.cards.libraries.gameplay.StakeTier
import kotlin.test.Test
import kotlin.test.assertEquals

class StakeTierDifficultyTest {

    @Test
    fun lowTiersPlayCasual_midPlaysStandard_highTiersPlayChallenging() {
        assertEquals(BotDifficulty.Casual, StakeTier.Practice.toBotDifficulty())
        assertEquals(BotDifficulty.Casual, StakeTier.Casual.toBotDifficulty())
        assertEquals(BotDifficulty.Standard, StakeTier.Standard.toBotDifficulty())
        assertEquals(BotDifficulty.Challenging, StakeTier.High.toBotDifficulty())
        assertEquals(BotDifficulty.Challenging, StakeTier.Premium.toBotDifficulty())
    }

    @Test
    fun buyInFloorsToItsTier_thenScalesDifficulty() {
        // Exact named buy-ins.
        assertEquals(BotDifficulty.Casual, StakeTier.fromBuyIn(StakeTier.Casual.buyIn).toBotDifficulty())
        assertEquals(BotDifficulty.Challenging, StakeTier.fromBuyIn(StakeTier.Premium.buyIn).toBotDifficulty())

        // A custom buy-in between Standard (5k) and High (20k) floors to Standard.
        assertEquals(StakeTier.Standard, StakeTier.fromBuyIn(9_000))
        assertEquals(BotDifficulty.Standard, StakeTier.fromBuyIn(9_000).toBotDifficulty())

        // Above every named tier clamps to the top tier.
        assertEquals(StakeTier.Premium, StakeTier.fromBuyIn(1_000_000))

        // Below the cheapest tier clamps up to it rather than falling through.
        assertEquals(StakeTier.Practice, StakeTier.fromBuyIn(1))
    }
}
