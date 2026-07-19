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
 *  2. **Real on shippable builds, fake on debug.** Release / internal builds
 *     default to the real flow so the money path is exercised end-to-end
 *     (sandbox receipts in TestFlight). **Debug builds default to the fake** via
 *     [debugOverride]: a sideloaded dev build has no provisioned Play / StoreKit
 *     catalog, so the real client returns nothing and the shop renders empty
 *     (SHOP-11). The fake stands in with seeded chip-pack SKUs so the shop is
 *     testable off-store. A dev who specifically wants the real store on a debug
 *     build flips the QA override on. A stray "on" is still safe: a server
 *     without store credentials refuses every receipt rather than trusting one.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
// Open so unit tests (which run on the debug variant, where [debugOverride]
// otherwise outranks an injected config value) can neutralize the override and
// exercise both the real and fake credit paths.
open class RealPurchasesEnabled(appConfigMap: AppConfigMap) : FlagConfigValue(appConfigMap) {
    override val name = "Real purchases enabled"
    override val path = "billing.realPurchasesEnabled"
    override val default = true
    override val debugOverride: Boolean? = false
}
