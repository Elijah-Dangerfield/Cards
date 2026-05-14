package com.dangerfield.cards.features.upgrade

import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.FeatureConfig

/**
 * Config values that gate the app at launch: minimum supported client version
 * and the maintenance switch. These are declared client-side with safe
 * defaults; the server can override them by sending the matching JSON paths
 * (e.g. `{"upgrade": {"minSupportedVersionCode": 99}}`).
 */
class UpgradeConfig(configMap: AppConfigMap) : FeatureConfig(
    featureName = "upgrade",
    configMap = configMap,
) {
    /** Lowest VERSION_CODE we still serve. Older clients see the blocking upgrade screen. */
    val minSupportedVersionCode by featureValue(default = 1)

    /** "off", "banner", or "blocking" — controls whether [MaintenanceState] surfaces. */
    val maintenanceMode by featureValue(
        default = "off",
        allowedValues = listOf("off", "banner", "blocking"),
    )

    /** Body text shown for banner / blocking maintenance states. */
    val maintenanceMessage by featureValue(default = "We're updating the servers, back in a moment.")
}
