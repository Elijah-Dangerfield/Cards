package com.dangerfield.cards.libraries.identity

import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.LongConfigValue
import com.dangerfield.cards.libraries.config.QaConfigValue
import com.dangerfield.cards.libraries.config.StringConfigValue
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Onboarding bootstrap values delivered through the existing unauthenticated
 * app-config tree (`GET /v1/app-config` → [AppConfigMap]). Onboarding runs
 * *before* an account exists, so it can't call any authed endpoint — these
 * values let the name-prefill and starter-grant reveal render without one.
 *
 * Both carry a **sentinel default that means "unknown"** because
 * [com.dangerfield.cards.libraries.config.ConfiguredValue] can't express a null
 * default (`T : Any`). Read them through [OnboardingStarterGrant.amountOrNull] /
 * [OnboardingSuggestedName.nameOrNull], which map the sentinel back to null:
 *
 *  - **Online:** the server populates the override and the real value resolves.
 *  - **Offline / pre-config:** the sentinel resolves → null → the client falls
 *    back to its own behavior (user types their own name; the grant page shows a
 *    "lands when you reconnect" treatment instead of a number).
 *
 * No display-name *rules* live here on purpose — the backend owns the rules and
 * the client enforces [com.dangerfield.cards.libraries.identity.profile.DisplayNameRules]
 * by convention. Any server-suggested name is expected to already satisfy them.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class OnboardingStarterGrant(appConfigMap: AppConfigMap) : LongConfigValue(appConfigMap) {
    override val name = "Onboarding starter grant"
    override val path = "onboarding.starterGrant"

    /** Sentinel — a real grant is always > 0, so 0 means "server hasn't told us yet". */
    override val default = UNKNOWN

    /** The configured starter-grant amount, or null when unknown (offline / pre-config). */
    fun amountOrNull(): Long? = value.takeIf { it > 0L }

    companion object {
        const val UNKNOWN: Long = 0L
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class OnboardingSuggestedName(appConfigMap: AppConfigMap) : StringConfigValue(appConfigMap) {
    override val name = "Onboarding suggested name"
    override val path = "onboarding.suggestedName"

    /** Sentinel — empty means "no server suggestion"; the client suggests its own. */
    override val default = ""

    /** The server-suggested display name, or null when none was provided. */
    fun nameOrNull(): String? = value.trim().takeIf { it.isNotEmpty() }
}
