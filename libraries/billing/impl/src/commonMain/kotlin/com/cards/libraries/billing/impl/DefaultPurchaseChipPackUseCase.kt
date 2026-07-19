package com.dangerfield.cards.libraries.billing.impl

import com.dangerfield.cards.libraries.billing.BillingClient
import com.dangerfield.cards.libraries.billing.BillingRepository
import com.dangerfield.cards.libraries.billing.IapPurchaseOutcome
import com.dangerfield.cards.libraries.billing.PurchaseChipPackUseCase
import com.dangerfield.cards.libraries.billing.PurchaseResult
import com.dangerfield.cards.libraries.billing.PurchaseTransaction
import com.dangerfield.cards.libraries.billing.RealPurchasesEnabled
import com.dangerfield.cards.libraries.billing.RedeemOutcome
import com.dangerfield.cards.libraries.billing.StoreKitCoordinator
import com.dangerfield.cards.libraries.billing.awaitUnfinishedTransactions
import com.dangerfield.cards.libraries.billing.toPurchaseResult
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductsRepository
import kotlinx.coroutines.flow.first
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding

/**
 * Default [PurchaseChipPackUseCase]. Drives one billing round-trip for the shop
 * grid and the in-game quick-buy sheet.
 *
 * Two credit paths, selected by [RealPurchasesEnabled] (BILL-5):
 *
 *  - **Real purchases on (default)** — validate -> grant -> reflect: the store
 *    confirms, the receipt is POSTed to `/v1/billing/redeem`, and the client
 *    reflects the server-returned authoritative balance via
 *    [ChipsRepository.setBalance]. The client never claims the chip amount and
 *    there's no local double-credit window. A rejected receipt (forged /
 *    unverifiable) grants nothing; an unreachable server leaves the purchase
 *    uncredited for a later retry/sync.
 *  - **Real purchases off** — credit chips locally on (fake) store
 *    confirmation, idempotent on the order id. This keeps the shop flow usable
 *    where the real store can't run (simulator, previews, unprovisioned Play
 *    listings).
 */
@ContributesBinding(AppScope::class)
@Inject
class DefaultPurchaseChipPackUseCase(
    private val billingClient: BillingClient,
    private val billingRepository: BillingRepository,
    private val chipsRepository: ChipsRepository,
    private val authRepository: AuthRepository,
    private val realPurchasesEnabled: RealPurchasesEnabled,
    private val storeKitCoordinator: StoreKitCoordinator,
    private val productsRepository: ProductsRepository,
) : PurchaseChipPackUseCase {

    private val logger = KLog.withTag("PurchaseChipPackUseCase")

    override suspend fun invoke(pack: Product.ChipPack): IapPurchaseOutcome {
        logger.logEvent("purchase.initiated", "product_id" to pack.id)
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
        val outcome = when (val result = billingClient.purchase(sku = pack.store.sku, userId = userId)) {
            is PurchaseResult.Success -> grant(pack, result.transaction, alreadyOwned = false)
            is PurchaseResult.AlreadyOwned -> grant(pack, result.transaction, alreadyOwned = true)
            PurchaseResult.UserCancelled -> IapPurchaseOutcome.Cancelled
            is PurchaseResult.Failed -> {
                // Error on purpose: a store-side purchase failure is lost
                // revenue and SentryLogTree only raises events at Error.
                logger.e { "Store purchase failed for ${pack.id}: ${result.reason}" }
                IapPurchaseOutcome.Failed(result.reason)
            }
            PurchaseResult.NotConnected -> {
                // Warn (breadcrumb only) — usually just offline / no store
                // account, not something to page on.
                logger.w { "Store not connected for purchase of ${pack.id}" }
                IapPurchaseOutcome.StoreUnavailable
            }
        }
        logPurchaseEvent(pack, outcome)
        return outcome
    }

    private fun logPurchaseEvent(pack: Product.ChipPack, outcome: IapPurchaseOutcome) {
        when (outcome) {
            is IapPurchaseOutcome.Success, is IapPurchaseOutcome.AlreadyOwned ->
                logger.logEvent("purchase.completed", "product_id" to pack.id)
            is IapPurchaseOutcome.Failed ->
                logger.logEvent("purchase.failed", "product_id" to pack.id, "error" to outcome.reason)
            IapPurchaseOutcome.Cancelled ->
                logger.logEvent("purchase.cancelled", "product_id" to pack.id)
            IapPurchaseOutcome.StoreUnavailable ->
                logger.logEvent("purchase.failed", "product_id" to pack.id, "error" to "store_unavailable")
            IapPurchaseOutcome.NotSignedIn, IapPurchaseOutcome.ClaimAccountRequired -> Unit
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
                    reflectBalance(redeem.balance, transaction.orderId)
                    finishTerminal(transaction)
                    logger.i {
                        "Redeemed ${redeem.grantedChips} chips for order ${transaction.orderId} " +
                            "(alreadyRedeemed=${redeem.alreadyRedeemed})"
                    }
                    outcome(redeem.grantedChips, alreadyOwned = alreadyOwned || redeem.alreadyRedeemed)
                }
                RedeemOutcome.Dead -> {
                    // Error on purpose: the user PAID and got nothing — the
                    // server terminally refused the receipt (forged, wrong
                    // product, revoked). Finish the transaction so it stops
                    // shadowing the user's next purchase attempt (a stuck
                    // unfinished consumable is what StoreKit hands back on the
                    // next buy of the same SKU — BILL-13); retrying it can never
                    // succeed.
                    logger.e { "Server terminally rejected receipt for order ${transaction.orderId} — finishing it" }
                    finishTerminal(transaction)
                    IapPurchaseOutcome.Failed(IapPurchaseOutcome.Failed.REASON_RECEIPT_REJECTED)
                }
                RedeemOutcome.Mismatch -> {
                    // The interactive buyer is a signed-in account, so a genuine
                    // receipt carries their id. A mismatch here means the receipt
                    // is bound to someone else — finish it rather than replay.
                    // Grant-on-replay is reserved for the StoreKit-drain path.
                    logger.e { "Receipt for order ${transaction.orderId} bound to another account — finishing it" }
                    finishTerminal(transaction)
                    IapPurchaseOutcome.Failed(IapPurchaseOutcome.Failed.REASON_RECEIPT_REJECTED)
                }
                RedeemOutcome.Transient -> {
                    // Error on purpose: paid but uncredited until the
                    // launch-time redeemer drains the unfinished transaction —
                    // worth seeing every time it happens. Left unfinished so a
                    // later retry can still redeem it.
                    logger.e { "Redeem unresolved for order ${transaction.orderId} — left uncredited for retry" }
                    IapPurchaseOutcome.Failed(IapPurchaseOutcome.Failed.REASON_REDEEM_UNAVAILABLE)
                }
            }
        }

        creditChipsLocally(pack, transaction)
        billingClient.consume(transaction.purchaseToken)
        return outcome(pack.grantsChips, alreadyOwned = alreadyOwned)
    }

    private fun outcome(grantedChips: Long, alreadyOwned: Boolean): IapPurchaseOutcome =
        if (alreadyOwned) {
            IapPurchaseOutcome.AlreadyOwned(grantedChips = grantedChips)
        } else {
            IapPurchaseOutcome.Success(grantedChips = grantedChips)
        }

    /**
     * Finish (consume / StoreKit `Transaction.finish()`) a transaction that
     * reached a terminal outcome — granted, dead, or wedged. This is the step
     * that unblocks the user: an unfinished consumable replays every launch and
     * shadows the next purchase of the same SKU (BILL-13), so it must run on
     * every terminal branch and can never be skipped by an earlier failure. Only
     * transient outcomes are left unfinished, to retry.
     */
    private suspend fun finishTerminal(transaction: PurchaseTransaction) {
        billingClient.consume(transaction.purchaseToken)
    }

    /**
     * Reflect the server's authoritative post-grant balance locally. The grant
     * itself is durable and idempotent server-side, so a failure here is only a
     * stale local display that the next [ChipsRepository] sync reconciles — it
     * must never block [finishTerminal], or a granted transaction would replay
     * forever.
     */
    private suspend fun reflectBalance(balance: Long, orderId: String) {
        Catching { chipsRepository.setBalance(balance) }.getOrElse { error ->
            logger.w(error) { "Failed to reflect balance for order $orderId; sync will reconcile" }
        }
    }

    override suspend fun redeemOutstanding() {
        if (!realPurchasesEnabled()) return
        val transactions = storeKitCoordinator.awaitUnfinishedTransactions()
            .map { it.toPurchaseResult() }
            .mapNotNull { result ->
                when (result) {
                    is PurchaseResult.Success -> result.transaction
                    is PurchaseResult.AlreadyOwned -> result.transaction
                    else -> null
                }
            }
        if (transactions.isEmpty()) return
        if (authRepository.current() !is AuthState.Authenticated) {
            logger.w { "${transactions.size} unfinished purchase(s) waiting but no session — retrying next launch" }
            return
        }
        val packs = Catching { productsRepository.observeCatalog().first() }
            .getOrNull()?.chipPacks.orEmpty()

        transactions.forEach { transaction ->
            val pack = packs.firstOrNull { it.store.sku == transaction.sku }
            if (pack == null) {
                logger.e {
                    "Unfinished purchase ${transaction.orderId} has no catalog product " +
                        "for sku ${transaction.sku} — leaving it for a future catalog"
                }
                return@forEach
            }
            logger.i { "Retrying uncredited purchase ${transaction.orderId} (${pack.id})" }
            // replayed = true: this is a store-replayed, unfinished transaction,
            // so the server may relax the account binding (grant-on-replay) to
            // recover a paid-but-wrong-account receipt to the current caller.
            when (val redeem = billingRepository.redeem(pack.id, transaction, replayed = true)) {
                is RedeemOutcome.Granted -> {
                    reflectBalance(redeem.balance, transaction.orderId)
                    finishTerminal(transaction)
                    logger.i {
                        "Recovered uncredited purchase ${transaction.orderId}: " +
                            "${redeem.grantedChips} chips (alreadyRedeemed=${redeem.alreadyRedeemed})"
                    }
                    logger.logEvent("purchase.recovered", "product_id" to pack.id)
                }
                RedeemOutcome.Dead -> {
                    // The replayed receipt will never validate — forged, wrong
                    // product, or revoked/refunded. Finishing it stops the
                    // every-launch replay loop AND unblocks new purchases of the
                    // same SKU, instead of leaving the shop permanently broken
                    // (BILL-13).
                    logger.e { "Terminally rejected replayed receipt for order ${transaction.orderId} — finishing it" }
                    finishTerminal(transaction)
                    logger.logEvent("purchase.discarded", "product_id" to pack.id)
                }
                RedeemOutcome.Mismatch -> {
                    // The replayed receipt is genuine and paid but bound to a
                    // prior identity we can't link on this caller. Finishing it
                    // stops the replay loop and unblocks new purchases (BILL-13);
                    // recovering the entitlement to the current account is
                    // grant-on-replay / sign-in-to-claim, layered on next.
                    logger.e { "Replayed receipt for order ${transaction.orderId} bound to another account — finishing it" }
                    finishTerminal(transaction)
                    logger.logEvent("purchase.discarded", "product_id" to pack.id)
                }
                RedeemOutcome.Transient ->
                    // Unresolved (unreachable server, 5xx, catalog drift): left
                    // unfinished so a later launch can retry it.
                    logger.w { "Redeem unresolved for replayed order ${transaction.orderId} — retrying next launch" }
            }
        }
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
