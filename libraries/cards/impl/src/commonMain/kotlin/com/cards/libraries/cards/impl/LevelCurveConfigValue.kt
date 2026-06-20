package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.DefaultLevelCurve
import com.dangerfield.cards.libraries.cards.LevelCurve
import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.JsonConfigValue
import com.dangerfield.cards.libraries.config.QaConfigValue
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The XP-per-level curve as a single structured config path. Hidden from the QA
 * dashboard (a JSON blob can't round-trip the per-key toggles) — retune it via
 * the server config tree, not the QA menu. Falls back to [DefaultLevelCurve]
 * (`100 × N²`) on a missing or undecodable remote value.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class LevelCurveConfigValue(appConfigMap: AppConfigMap) : JsonConfigValue<LevelCurve>(
    appConfigMap = appConfigMap,
    serializer = LevelCurve.serializer(),
) {
    override val name = "Level curve"
    override val path = "progression.levelCurve"
    override val default = DefaultLevelCurve
}
