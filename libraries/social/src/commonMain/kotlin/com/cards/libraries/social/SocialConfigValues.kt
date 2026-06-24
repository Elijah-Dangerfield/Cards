package com.dangerfield.cards.libraries.social

import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.FlagConfigValue
import com.dangerfield.cards.libraries.config.QaConfigValue
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Master kill switch for the friends/social graph. Friends, presence, the
 * "recently played with" shelf, and the friend-request inbox are descoped to
 * V2 (gated on SOC-2 work), so this defaults off: every social surface hides
 * when it's false rather than rendering disabled. The code paths still ship —
 * reviving in V2 is a server-side config flip, not a re-implementation.
 *
 * Consumers (Home shelves, the Profile inbox, the at-table "Add friend"
 * affordance) read this flag and skip rendering when it resolves false.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class SocialEnabled(appConfigMap: AppConfigMap) : FlagConfigValue(appConfigMap) {
    override val name = "Social features enabled"
    override val path = "social.enabled"
    override val default = false

    companion object {
        /**
         * Builds a [SocialEnabled] resolving to a fixed [enabled] value, so tests
         * can supply the flag without standing up an [AppConfigMap]. Production
         * always goes through the `@Inject` constructor.
         */
        fun forTest(enabled: Boolean): SocialEnabled =
            SocialEnabled(
                object : AppConfigMap() {
                    override val map: Map<String, *> = mapOf("social" to mapOf("enabled" to enabled))
                },
            )
    }
}
