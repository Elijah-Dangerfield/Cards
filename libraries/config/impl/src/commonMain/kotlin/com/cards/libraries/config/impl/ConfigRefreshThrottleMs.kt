package com.dangerfield.cards.libraries.config.impl

import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.LongConfigValue
import com.dangerfield.cards.libraries.config.QaConfigValue
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Minimum gap between config fetches, in milliseconds. The app refreshes config
 * on every foreground but skips the fetch if less than this has passed since the
 * last one (see `OfflineFirstAppConfigRepository`).
 *
 * Meta on purpose: config controls how often config refreshes. The in-code
 * default is 5 minutes (a safe prod value); the **dev** database overrides it to
 * `0` so a flag change shows up on the very next foreground while testing. Set it
 * per-environment from the admin tool like any other flag. `0` = refetch on every
 * foreground.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class ConfigRefreshThrottleMs(appConfigMap: AppConfigMap) : LongConfigValue(appConfigMap) {
    override val name = "Config refresh throttle (ms)"
    override val path = "config.refreshThrottleMs"
    override val default = 5L * 60L * 1000L // 5 minutes
}
