package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.DefaultLevelRewards
import com.dangerfield.cards.libraries.cards.LevelRewardsConfig
import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.JsonConfigValue
import com.dangerfield.cards.libraries.config.QaConfigValue
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The `level → rewards` table as a single structured config path. Hidden from
 * the QA dashboard (a JSON blob can't round-trip the per-key toggles) — retune
 * it via the server config tree, not the QA menu. Falls back to
 * [DefaultLevelRewards] on a missing or undecodable remote value.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class LevelRewardsConfigValue(appConfigMap: AppConfigMap) : JsonConfigValue<LevelRewardsConfig>(
    appConfigMap = appConfigMap,
    serializer = LevelRewardsConfig.serializer(),
) {
    override val name = "Level rewards"
    override val path = "progression.levelRewards"
    override val default = DefaultLevelRewards
}
