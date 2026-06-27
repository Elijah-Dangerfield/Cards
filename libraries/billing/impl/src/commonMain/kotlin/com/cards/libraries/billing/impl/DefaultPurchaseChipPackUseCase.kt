package com.dangerfield.cards.libraries.billing.impl

import com.dangerfield.cards.libraries.billing.BillingClient
import com.dangerfield.cards.libraries.billing.BillingRepository
import com.dangerfield.cards.libraries.billing.IapPurchaseOutcome
import com.dangerfield.cards.libraries.billing.PurchaseChipPackUseCase
import com.dangerfield.cards.libraries.billing.PurchaseResult
import com.dangerfield.cards.libraries.billing.PurchaseTransaction
import com.dangerfield.cards.libraries.billing.RealPurchasesEnabled
import com.dangerfield.cards.libraries.billing.RedeemOutcome
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.products.Product
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding

/**
 * Default [PurchaseChipPackUseCase]. Drives one billing round-trip for the shop
 * grid and the in-game quick-buy sheet.
 *
 * Two credit paths, selected by [RealPurchasesEnabled] (BILL-5):
 *
 *  - **Real purchases on** — validate -> grant -> reflect: the store confirms,
 *    the receipt is POSTed to `/v1/billing/redeem`, and the client reflects the
 *    server-returned authoritative balance via [ChipsRepository.setBalance].
 *    The client never claims the chip amount and there's no local double-credit
 *    window. A rejected receipt (forged / unverifiable) grants nothing; an
 *    unreachable server leaves the purchase uncredited for a later retry/sync.
 *  - **Real purchases off (default)** — credit chips locally on store
 *    confirmation, idempotent on the order id. This keeps the dev / Fake flow
 *    exercising the full path end-to-end while real store listings and the real
 *    receipt validators (BILL-2/3/4) aren't live, and ships real billing dark.
 */
@ContributesBinding(AppScope::class)
@Inject
class DefaultPurchaseChipPackUseCase(
    private val billingClient: BillingClient,
    private val billingRepository: BillingRepository,
    private val chipsRepository: ChipsRepository,
    private val authRepository: AuthRepository,
    private val realPurchasesEnabled: RealPurchasesEnabled,
) : PurchaseChipPackUseCase {

    private val logger = KLog.withTag("PurchaseChipPackUseCase")

    override suspend fun invoke(pack: Product.ChipPack): IapPurchaseOutcome {
        val authenticated = authRepository.current() as? AuthState.Authenticated
        if (authenticated == null) {
            logger.w { "IAP purchase requested with no signed-in user" }
            return IapPurchaseOutcome.NotSignedIn
        }
        if (authenticated.isAnonymous) {
            logger.i { "IAP purchase blocked for anonymous user — routing to account claim" }
            return IapPurchaseOutcome.ClaimAccountRequired
        }
        val userId = authenticated.userId
        return when (val result = billingClient.purchase(sku = pack.store.sku, userId = userId)) {
            is PurchaseResult.Success -> grant(pack, result.transaction, alreadyOwned = false)
            is PurchaseResult.AlreadyOwned -> grant(pack, result.transaction, alreadyOwned = true)
            PurchaseResult.UserCancelled -> IapPurchaseOutcome.Cancelled
            is PurchaseResult.Failed -> IapPurchaseOutcome.Failed(result.reason)
            PurchaseResult.NotConnected -> IapPurchaseOutcome.StoreUnavailable
        }
    }

    private suspend fun grant(
        pack: Product.ChipPack,
        transaction: PurchaseTransaction,
        alreadyOwned: Boolean,
    ): IapPurchaseOutcome {
        if (realPurchasesEnabled()) {
            return when (val redeem = billingRepository.redeem(pack.id, transaction)) {
                is RedeemOutcome.Granted -> {
                    chipsRepository.setBalance(redeem.balance)
                    billingClient.acknowledge(transaction.purchaseToken)
                    logger.i {
                        "Redeemed ${redeem.grantedChips} chips for order ${transaction.orderId} " +
                            "(alreadyRedeemed=${redeem.alreadyRedeemed})"
                    }
                    outcome(redeem.grantedChips, alreadyOwned = alreadyOwned || redeem.alreadyRedeemed)
                }
                RedeemOutcome.Rejected -> {
                    logger.w { "Server rejected receipt for order ${transaction.orderId} — no credit" }
                    IapPurchaseOutcome.Failed("receipt_rejected")
                }
                RedeemOutcome.Unavailable -> {
                    logger.w { "Redeem unreachable for order ${transaction.orderId} — left uncredited" }
                    IapPurchaseOutcome.Failed("redeem_unavailable")
                }
            }
        }

        creditChipsLocally(pack, transaction)
        billingClient.acknowledge(transaction.purchaseToken)
        return outcome(pack.grantsChips, alreadyOwned = alreadyOwned)
    }

    private fun outcome(grantedChips: Long, alreadyOwned: Boolean): IapPurchaseOutcome =
        if (alreadyOwned) {
            IapPurchaseOutcome.AlreadyOwned(grantedChips = grantedChips)
        } else {
            IapPurchaseOutcome.Success(grantedChips = grantedChips)
        }

    private suspend fun creditChipsLocally(pack: Product.ChipPack, transaction: PurchaseTransaction) {
        chipsRepository.addChips(
            amount = pack.grantsChips,
            reason = "iap.${pack.id}",
            idempotencyKey = "iap.${pack.id}.${transaction.orderId}",
        )
        logger.i { "Granted ${pack.grantsChips} chips locally for IAP order ${transaction.orderId}" }
    }
}
