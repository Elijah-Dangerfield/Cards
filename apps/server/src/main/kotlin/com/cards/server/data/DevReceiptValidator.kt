package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.PurchaseEnvironment
import com.dangerfield.cards.server.domain.PurchaseReceipt
import com.dangerfield.cards.server.domain.ReceiptValidation
import com.dangerfield.cards.server.domain.ReceiptValidator
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Default [ReceiptValidator] binding for dev / local IAP testing.
 *
 * It does NOT verify anything with a platform store — it trusts the
 * client and uses the receipt token as the store transaction id, which is
 * exactly what makes a local StoreKit `.storekit` config or Play
 * static-response SKU exercise the full redeem path without real
 * credentials. The grant is still idempotent (the token-as-order-id flows
 * into the `(store, order_id)` unique constraint) and still wallet-ledgered.
 *
 * This must NOT ship to a real-money environment. BILL-2 replaces it with
 * the Apple + Google validators:
 * `@ContributesBinding(ServerScope::class, replaces = [DevReceiptValidator::class])`.
 * Until those land, the BILL-5 `billing.realPurchasesEnabled` config gate
 * keeps the redeem path dark in production.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class DevReceiptValidator : ReceiptValidator {
    override suspend fun validate(request: PurchaseReceipt): ReceiptValidation =
        if (request.token.isBlank()) {
            ReceiptValidation.Invalid("empty_token")
        } else {
            // Nothing verified against a real store is by definition not revenue.
            ReceiptValidation.Valid(orderId = request.token, environment = PurchaseEnvironment.Sandbox)
        }
}
