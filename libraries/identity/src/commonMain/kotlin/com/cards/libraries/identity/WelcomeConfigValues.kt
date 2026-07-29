package com.dangerfield.cards.libraries.identity

import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.LongConfigValue
import com.dangerfield.cards.libraries.config.QaConfigValue
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Epoch-ms at which the founding-member welcome window closes. While `now` is
 * before it, the Home welcome dialog wears its founding-member copy — thanks for
 * being early, the review / word-of-mouth ask, where feedback lands — and shows
 * once to *every* user who hasn't seen it, existing early players included. After
 * it passes, the dialog reverts to the plain new-account welcome.
 *
 * Stored as epoch-ms so it rides the existing scalar config tree with no new
 * value type, matching the epoch-ms convention the rest of the app's time fields
 * use. Compared against a plain device clock ([isActiveAt]): this only selects
 * copy, so a spun-back wall clock merely prolongs a thank-you — never anything
 * money- or access-sensitive, which is where a monotonic anchor would be needed.
 *
 * Default [DISABLED] (`0`) is the "no window" sentinel: the founding copy never
 * shows and the dialog is the plain welcome for everyone.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class WelcomeFoundingMemberUntil(appConfigMap: AppConfigMap) : LongConfigValue(appConfigMap) {
    override val name = "Founding-member welcome window closes at (epoch-ms)"
    override val path = "welcome.foundingMemberUntilEpochMs"

    override val default = DISABLED

    /** True when [nowEpochMs] falls inside a configured founding window. */
    fun isActiveAt(nowEpochMs: Long): Boolean = value > DISABLED && nowEpochMs < value

    companion object {
        const val DISABLED: Long = 0L

        /**
         * Builds a [WelcomeFoundingMemberUntil] resolving to a fixed window-close
         * timestamp, so tests can open or close the window without standing up an
         * [AppConfigMap]. Production always goes through the `@Inject` constructor.
         */
        fun forTest(untilEpochMs: Long): WelcomeFoundingMemberUntil =
            WelcomeFoundingMemberUntil(
                object : AppConfigMap() {
                    override val map: Map<String, *> =
                        mapOf("welcome" to mapOf("foundingMemberUntilEpochMs" to untilEpochMs))
                },
            )
    }
}
