package com.dangerfield.cards.libraries.rooms

import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.FlagConfigValue
import com.dangerfield.cards.libraries.config.QaConfigValue
import com.dangerfield.cards.libraries.config.StringConfigValue
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class StandingTablesEnabled(appConfigMap: AppConfigMap) : FlagConfigValue(appConfigMap) {
    override val name = "Standing tables enabled"
    override val path = "standingTables.enabled"
    override val default = false

    companion object {
        fun forTest(enabled: Boolean) = StandingTablesEnabled(object : AppConfigMap() {
            override val map: Map<String, *> = mapOf("standingTables" to mapOf("enabled" to enabled))
        })
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class InviteShareBaseUrl(appConfigMap: AppConfigMap) : StringConfigValue(appConfigMap) {
    override val name = "Invite share base URL"
    override val path = "invite.shareBaseUrl"
    override val default = "https://downcard.app/join"

    companion object {
        fun forTest(url: String) = InviteShareBaseUrl(object : AppConfigMap() {
            override val map: Map<String, *> = mapOf("invite" to mapOf("shareBaseUrl" to url))
        })
    }
}
