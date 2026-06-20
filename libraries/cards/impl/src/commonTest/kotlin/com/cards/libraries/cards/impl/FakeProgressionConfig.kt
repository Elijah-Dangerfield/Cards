package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.DefaultLevelCurve
import com.dangerfield.cards.libraries.cards.LevelCurve
import com.dangerfield.cards.libraries.cards.LevelReward
import com.dangerfield.cards.libraries.cards.ProgressionConfig

/** Test [ProgressionConfig] — no rewards and the bundled default curve. */
internal class FakeProgressionConfig(
    private val curve: LevelCurve = DefaultLevelCurve,
    private val rewards: (Int) -> List<LevelReward> = { emptyList() },
) : ProgressionConfig {
    override fun rewardsForLevel(level: Int): List<LevelReward> = rewards(level)
    override fun levelCurve(): LevelCurve = curve
}
