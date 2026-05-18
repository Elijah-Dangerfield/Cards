package com.dangerfield.cards.libraries.identity

import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.FeatureConfig

/**
 * Remote-toggleable flags for auth surfaces. Both default to false because
 * the corresponding Supabase Auth providers aren't enabled in any project
 * until the dashboard gets the OAuth credentials (Apple Developer service
 * id / Google Cloud client id). Once provisioned, server-side AppConfig
 * flips these on without a client release.
 *
 * Code consumers (sign-in screen, claim screen) hide their respective
 * buttons when the flag is false — the OAuth code paths still ship but
 * stay dormant. This avoids "Coming soon" buttons that frustrate users.
 *
 * See docs/decisions.md "Identity pivot (REVERSED)" for the broader
 * provider story.
 */
class IdentityFeatureConfig(configMap: AppConfigMap) : FeatureConfig(
    featureName = "identity",
    configMap = configMap,
) {
    val googleSignInEnabled by featureValue(default = false)
    val appleSignInEnabled by featureValue(default = false)
}
