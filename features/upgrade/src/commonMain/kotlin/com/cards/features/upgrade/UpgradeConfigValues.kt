package com.dangerfield.cards.features.upgrade

import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.QaConfigValue
import com.dangerfield.cards.libraries.config.IntConfigValue
import com.dangerfield.cards.libraries.config.StringConfigValue
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Config values that gate the app at launch: minimum supported client version
 * and the maintenance switch. Declared client-side with safe defaults; the
 * server overrides them by sending the matching JSON paths (e.g.
 * `{"upgrade": {"minSupportedVersionCode": 99}}`). They group under "upgrade"
 * in the QA menu automatically via their shared path prefix.
 *
 * [AppGuardState.from] resolves these against the *streamed* config so a
 * maintenance toggle takes effect live without an app restart.
 */

/** Lowest VERSION_CODE we still serve. Older clients see the blocking upgrade screen. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class MinSupportedVersionCode(appConfigMap: AppConfigMap) : IntConfigValue(appConfigMap) {
    override val name = "Min supported version code"
    override val path = "upgrade.minSupportedVersionCode"
    override val default = 1
}

/** "off", "banner", or "blocking" — controls whether maintenance state surfaces. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class MaintenanceMode(appConfigMap: AppConfigMap) : StringConfigValue(appConfigMap) {
    override val name = "Maintenance mode"
    override val path = "upgrade.maintenanceMode"
    override val default = "off"
    override val allowedValues = listOf("off", "banner", "blocking")
}

/** Body text shown for banner / blocking maintenance states. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class MaintenanceMessage(appConfigMap: AppConfigMap) : StringConfigValue(appConfigMap) {
    override val name = "Maintenance message"
    override val path = "upgrade.maintenanceMessage"
    override val default = "We're updating the servers, back in a moment."
}
