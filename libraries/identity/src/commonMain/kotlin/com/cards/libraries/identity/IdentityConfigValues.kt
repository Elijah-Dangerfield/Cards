package com.dangerfield.cards.libraries.identity

import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.QaConfigValue
import com.dangerfield.cards.libraries.config.FlagConfigValue
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Remote-toggleable flags for auth surfaces. Both default to false because the
 * corresponding Supabase Auth providers aren't enabled in any project until the
 * dashboard gets the OAuth credentials (Apple Developer service id / Google
 * Cloud client id). Once provisioned, server-side AppConfig flips these on
 * without a client release.
 *
 * Code consumers (sign-in screen, claim screen) hide their respective buttons
 * when the flag is false — the OAuth code paths still ship but stay dormant.
 * This avoids "Coming soon" buttons that frustrate users.
 *
 * See docs/decisions.md "Identity pivot (REVERSED)" for the broader provider story.
 */

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class GoogleSignInEnabled(appConfigMap: AppConfigMap) : FlagConfigValue(appConfigMap) {
    override val name = "Google sign-in enabled"
    override val path = "identity.googleSignInEnabled"
    override val default = false
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class AppleSignInEnabled(appConfigMap: AppConfigMap) : FlagConfigValue(appConfigMap) {
    override val name = "Apple sign-in enabled"
    override val path = "identity.appleSignInEnabled"
    // On by default — the native Apple flow is wired and the button is iOS-gated.
    // (Still needs the Apple provider configured in the Supabase project to
    // actually authenticate; AppConfig / QA override can flip it back off.)
    override val default = true
}
