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
 *  1. **Picks the billing client + credit path.** When on, purchases run the
 *     real platform store (StoreKit / Play Billing) and the flow is validate ->
 *     grant -> reflect: the client POSTs the receipt to `/v1/billing/redeem`
 *     and reflects the server-returned authoritative balance, so a forged
 *     receipt can't mint chips and there's no local double-credit window. When
 *     off, a `FakeBillingClient` stands in for the store and a successful
 *     "purchase" credits chips locally.
 *  2. **Defaults to the real flow.** The money path is the one worth testing,
 *     so by default every build exercises it end-to-end (sandbox receipts in
 *     dev / TestFlight). Flip off — usually via the QA override — only when the
 *     real store is in the way (simulator shop iteration, previews, Play
 *     listings not yet provisioned). A stray "on" is still safe: a server
 *     without store credentials refuses every receipt rather than trusting one.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class RealPurchasesEnabled(appConfigMap: AppConfigMap) : FlagConfigValue(appConfigMap) {
    override val name = "Real purchases enabled"
    override val path = "billing.realPurchasesEnabled"
    override val default = true
}
