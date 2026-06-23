package com.dangerfield.cards.libraries.billing.impl

import com.dangerfield.cards.libraries.billing.BillingClient
import com.dangerfield.cards.libraries.billing.IapPurchaseOutcome
import com.dangerfield.cards.libraries.billing.PurchaseChipPackUseCase
import com.dangerfield.cards.libraries.billing.PurchaseResult
import com.dangerfield.cards.libraries.billing.PurchaseTransaction
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.products.Product
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding

/**
 * Default [PurchaseChipPackUseCase]. Extracted verbatim from
 * `ShopViewModel.launchIapPurchase` / `creditChipsFor` so the shop grid and the
 * in-game quick-buy sheet drive identical billing behavior.
 *
 * V1 simplification: credit chips locally as soon as the platform store
 * confirms. Server-side receipt validation + chip ledger lands with the
 * auth-gated `/v1/billing/redeem` endpoint; until then this is the source of
 * truth. The order id doubles as the idempotency key so a duplicate
 * purchase-confirmed signal (e.g. resume-after-restore) doesn't double-credit
 * when the wallet sync flushes either copy of the event.
 */
@ContributesBinding(AppScope::class)
@Inject
class DefaultPurchaseChipPackUseCase(
    private val billingClient: BillingClient,
    private val chipsRepository: ChipsRepository,
    private val authRepository: AuthRepository,
) : PurchaseChipPackUseCase {

    private val logger = KLog.withTag("PurchaseChipPackUseCase")

    override suspend fun invoke(pack: Product.ChipPack): IapPurchaseOutcome {
        val authenticated = authRepository.current() as? AuthState.Authenticated
        if (authenticated == null) {
            logger.w { "IAP purchase requested with no signed-in user" }
            return IapPurchaseOutcome.NotSignedIn
        }
        if (authenticated.isAnonymous) {
            // Real-money IAP is gated behind account claim: an anonymous user
            // can't buy until they've linked email/Apple, removing the "paid
            // then lost the account" risk at the source. Caller routes to the
            // claim flow instead of the platform purchase sheet.
            logger.i { "IAP purchase blocked for anonymous user — routing to account claim" }
            return IapPurchaseOutcome.ClaimAccountRequired
        }
        val userId = authenticated.userId
        return when (val result = billingClient.purchase(sku = pack.store.sku, userId = userId)) {
            is PurchaseResult.Success -> {
                creditChipsFor(pack, result.transaction)
                billingClient.acknowledge(result.transaction.purchaseToken)
                IapPurchaseOutcome.Success(grantedChips = pack.grantsChips)
            }
            is PurchaseResult.AlreadyOwned -> {
                // Treat as idempotent — re-credit so a client that lost track
                // of a previous purchase still gets its chips. Server-side
                // validation will dedupe by orderId once /v1/billing/redeem
                // ships; until then we accept the double-credit risk in V1.x.
                creditChipsFor(pack, result.transaction)
                billingClient.acknowledge(result.transaction.purchaseToken)
                IapPurchaseOutcome.AlreadyOwned(grantedChips = pack.grantsChips)
            }
            PurchaseResult.UserCancelled -> IapPurchaseOutcome.Cancelled
            is PurchaseResult.Failed -> IapPurchaseOutcome.Failed(result.reason)
            PurchaseResult.NotConnected -> IapPurchaseOutcome.StoreUnavailable
        }
    }

    private suspend fun creditChipsFor(pack: Product.ChipPack, transaction: PurchaseTransaction) {
        chipsRepository.addChips(
            amount = pack.grantsChips,
            reason = "iap.${pack.id}",
            idempotencyKey = "iap.${pack.id}.${transaction.orderId}",
        )
        logger.i { "Granted ${pack.grantsChips} chips for IAP order ${transaction.orderId}" }
    }
}
