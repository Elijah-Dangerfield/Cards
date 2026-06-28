package com.dangerfield.cards.libraries.billing

import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.FlagConfigValue
import com.dangerfield.cards.libraries.config.QaConfigValue
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Remote-toggleable gate for real-money IAP. Two jobs in one flag:
 *
 *  1. **Picks the credit path.** When off, a successful store purchase credits
 *     chips locally (the dev / Fake path used while store listings aren't
 *     provisioned). When on, the purchase flow becomes validate -> grant ->
 *     reflect: the client POSTs the receipt to `/v1/billing/redeem` and reflects
 *     the server-returned authoritative balance, so a forged receipt can't mint
 *     chips and there's no local double-credit window.
 *  2. **Ships real billing dark.** Default is off so the server-authoritative
 *     path stays disabled in prod until the real platform clients (BILL-3/4) and
 *     receipt validators (BILL-2) are live; flip on per-environment via AppConfig
 *     (or a QA override) without a client release.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class RealPurchasesEnabled(appConfigMap: AppConfigMap) : FlagConfigValue(appConfigMap) {
    override val name = "Real purchases enabled"
    override val path = "billing.realPurchasesEnabled"
    override val default = false
}
