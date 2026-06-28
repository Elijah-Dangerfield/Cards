package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.config.BillingConfig
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.PurchaseReceipt
import com.dangerfield.cards.server.domain.ReceiptValidation
import com.dangerfield.cards.server.domain.ReceiptValidator
import com.dangerfield.cards.server.domain.Store
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Production [ReceiptValidator] (BILL-2). Replaces [DevReceiptValidator] in
 * the DI graph and routes each receipt to the real platform validator by its
 * [Store].
 *
 * Each platform validator stays dormant — refusing validation — until its
 * credentials are configured, so an unconfigured server can never accept a
 * forged receipt by default. With the BILL-5 `billing.realPurchasesEnabled`
 * flag off (the default), the client still credits locally and never hits
 * `/v1/billing/redeem`, so an unconfigured store is harmless; once that flag
 * flips on, an unconfigured store simply rejects the redemption rather than
 * trusting it.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class, replaces = [DevReceiptValidator::class])
@Inject
class StoreReceiptValidator(config: BillingConfig) : ReceiptValidator {

    private val apple = AppStoreReceiptValidator(config)
    private val google = GooglePlayReceiptValidator(config)

    override suspend fun validate(request: PurchaseReceipt): ReceiptValidation =
        when (request.store) {
            Store.Apple -> apple.validate(request)
            Store.Google -> google.validate(request)
        }
}
