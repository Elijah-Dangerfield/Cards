package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.LevelReward
import com.dangerfield.cards.libraries.cards.ProgressionConfig
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Resolves [ProgressionConfig] off the app-config tree. Reads the live config
 * value on each call so a server / QA override takes effect without a restart;
 * the table is tiny, so re-resolving per lookup is cheap.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultProgressionConfig(
    private val levelRewards: LevelRewardsConfigValue,
) : ProgressionConfig {

    override fun rewardsForLevel(level: Int): List<LevelReward> =
        levelRewards().rewardsForLevel(level)
}
