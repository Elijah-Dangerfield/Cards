package com.dangerfield.cards.features.shop.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.billing.BillingClient
import com.dangerfield.cards.libraries.billing.PurchaseResult
import com.dangerfield.cards.libraries.billing.PurchaseTransaction
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.InventorySyncService
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.RedeemResult
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.IdentityState
import com.dangerfield.cards.libraries.products.CatalogTimeAnchor
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.ProductsRepository
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

/**
 * Backs the shop screen.
 *
 * Three streams are wired on init:
 *  - Catalog (server-driven what's-for-sale)
 *  - Chip balance (drives can-afford gating + "you have / after" preview)
 *  - Inventory (drives the "OWNED" badge on chip offers)
 *
 * Catalog refresh is kicked at init + on pull-to-refresh. Inventory sync
 * (locally-pending → server-confirmed) is also kicked at init — once auth
 * lands and the server tracks per-user state, that becomes the
 * reconciliation pass.
 *
 * Optimistic redemption flow ([ShopAction.ConfirmPendingPurchase] for a
 * chip-funded offer):
 *  1. Pre-check chip balance — if insufficient, surface error and close
 *     the sheet without mutating anything. (UI also disables the button
 *     when balance < cost so this path is defensive, not primary.)
 *  2. Call [InventoryRepository.redeemChipOffer] — it atomically inserts
 *     the inventory row as Pending and deducts chips.
 *  3. Close the sheet, emit a confirmation event for the UI to celebrate
 *     (toast, haptic, whatever).
 *  4. Best-effort fire [InventorySyncService.sync] in the background so
 *     the row flips Pending → Confirmed before the user closes the app.
 *
 * IAP packs ([ShopAction.ConfirmPendingPurchase] for a chip pack) drive
 * [BillingClient.purchase] and emit a [ShopEvent.PurchaseFinished] once
 * the store sheet resolves (success, cancel, failure). Chips are
 * credited locally via [ChipsRepository.applyDelta] when the store
 * confirms — server-side receipt validation lands later (shop-roadmap §2).
 */
class ShopViewModel @Inject constructor(
    private val productsRepository: ProductsRepository,
    private val inventoryRepository: InventoryRepository,
    private val inventorySyncService: InventorySyncService,
    private val chipsRepository: ChipsRepository,
    private val progressionRepository: ProgressionRepository,
    private val billingClient: BillingClient,
    private val identityRepository: IdentityRepository,
) : SEAViewModel<ShopState, ShopEvent, ShopAction>(initialStateArg = ShopState()) {

    private val logger = KLog.withTag("ShopViewModel")

    init {
        viewModelScope.launch {
            productsRepository.observeCatalog().collect { catalog ->
                takeAction(ShopAction.CatalogChanged(catalog))
            }
        }
        viewModelScope.launch {
            chipsRepository.observeBalance().collect { balance ->
                takeAction(ShopAction.ChipsChanged(balance))
            }
        }
        viewModelScope.launch {
            inventoryRepository.observeInventory().collect { inventory ->
                takeAction(ShopAction.InventoryChanged(inventory))
            }
        }
        viewModelScope.launch {
            // Player level is derived from lifetime XP via the cards
            // progression curve. Recomputed on every progression update
            // so the shop's lock state flips the moment the user levels
            // up mid-session.
            progressionRepository.observeProgression().collect { progression ->
                val level = levelProgressFor(progression.totalXp).level
                takeAction(ShopAction.PlayerLevelChanged(level))
            }
        }
        viewModelScope.launch {
            // Time anchor tracks the server's wall clock at the moment of
            // the latest catalog fetch, paired with a local monotonic
            // mark. Used by the countdown badge UI for sale-window
            // offers — see CatalogTimeAnchor for the clock-spoof-
            // resistance logic.
            productsRepository.observeTimeAnchor().collect { anchor ->
                takeAction(ShopAction.TimeAnchorChanged(anchor))
            }
        }
        // Kick off catalog refresh + inventory sync (these run concurrently).
        takeAction(ShopAction.Refresh)
        viewModelScope.launch { inventorySyncService.sync() }
    }

    override suspend fun handleAction(action: ShopAction) {
        when (action) {
            is ShopAction.Refresh -> {
                action.updateState { it.copy(isRefreshing = true, errorMessage = null) }
                viewModelScope.launch {
                    val result = productsRepository.refresh()
                    result.onFailure { failure ->
                        logger.w(failure) { "Catalog refresh failed" }
                        takeAction(ShopAction.RefreshFailed(failure.message ?: "Couldn't load shop"))
                    }
                    takeAction(ShopAction.RefreshFinished)
                }
            }
            is ShopAction.RefreshFinished -> action.updateState {
                it.copy(isRefreshing = false, hasLoaded = true)
            }
            is ShopAction.RefreshFailed -> action.updateState {
                it.copy(errorMessage = action.message)
            }
            is ShopAction.CatalogChanged -> action.updateState {
                it.copy(catalog = action.catalog)
            }
            is ShopAction.ChipsChanged -> action.updateState {
                it.copy(chipBalance = action.balance)
            }
            is ShopAction.InventoryChanged -> action.updateState {
                it.copy(
                    inventory = action.inventory,
                    ownedProductIds = action.inventory.map { item -> item.productId }.toSet(),
                )
            }
            is ShopAction.PlayerLevelChanged -> action.updateState {
                it.copy(playerLevel = action.level)
            }
            is ShopAction.TimeAnchorChanged -> action.updateState {
                it.copy(timeAnchor = action.anchor)
            }
            is ShopAction.DismissError -> action.updateState {
                it.copy(errorMessage = null)
            }

            // ---- Purchase intent flow ----

            is ShopAction.RequestPurchase -> {
                // Open the sheet for ANY non-expired state — Owned,
                // Locked, Insufficient, Available. The sheet renders
                // state-appropriate content + CTA, so users can learn
                // "what does this even do?" before they qualify to buy.
                //
                // Expired offers are the one exception: silently refresh
                // + surface a toast rather than opening a stale sheet.
                // The catalog filter drops it from the next response.
                val product = action.product
                if (state.isExpired(product)) {
                    sendEvent(ShopEvent.OfferExpired(product.id))
                    takeAction(ShopAction.Refresh)
                    return
                }
                action.updateState { it.copy(pendingPurchase = PendingPurchase.from(product)) }
            }
            is ShopAction.DismissPendingPurchase -> action.updateState {
                it.copy(pendingPurchase = null)
            }
            is ShopAction.ConfirmPendingPurchase -> {
                // Defense-in-depth: the sheet's primary CTA is disabled
                // for non-buyable states, but the action layer is the
                // final fence. Anything outside Available is a no-op.
                val pending = state.pendingPurchase ?: return
                val product: Product = when (pending) {
                    is PendingPurchase.IapPack -> pending.product
                    is PendingPurchase.ChipOffer -> pending.product
                }
                if (state.sheetModeFor(product) !is PurchaseSheetMode.Available) return
                if (state.isExpired(product)) {
                    sendEvent(ShopEvent.OfferExpired(product.id))
                    takeAction(ShopAction.Refresh)
                    return
                }
                when (pending) {
                    is PendingPurchase.IapPack -> {
                        // Close the sheet immediately — the platform store
                        // sheet is its own modal flow. Then drive the purchase
                        // through BillingClient and surface the outcome via
                        // ShopEvent.PurchaseFinished, which the screen renders
                        // as a toast / celebration cue.
                        //
                        // Catalog reconciliation (shop-roadmap §1) already
                        // dropped IAP packs the platform store doesn't
                        // recognize, so by the time we get here we trust the
                        // SKU exists in the store.
                        action.updateState { it.copy(pendingPurchase = null) }
                        launchIapPurchase(pending.product)
                    }
                    is PendingPurchase.ChipOffer -> {
                        confirmChipOfferRedeem(pending.product)
                    }
                }
            }
        }
    }

    private suspend fun launchIapPurchase(pack: Product.ChipPack) {
        val userId = (identityRepository.state.value as? IdentityState.SignedIn)?.identity?.userId
        if (userId == null) {
            logger.w { "IAP purchase requested with no signed-in user" }
            sendEvent(ShopEvent.PurchaseFinished(IapPurchaseOutcome.NotSignedIn))
            return
        }
        val result = billingClient.purchase(sku = pack.store.sku, userId = userId)
        val outcome = when (result) {
            is PurchaseResult.Success -> {
                creditChipsFor(pack, result.transaction)
                billingClient.acknowledge(result.transaction.purchaseToken)
                IapPurchaseOutcome.Success(grantedChips = pack.grantsChips)
            }
            is PurchaseResult.AlreadyOwned -> {
                // Treat as idempotent — re-credit so a client that lost
                // track of a previous purchase still gets its chips. Server-
                // side validation will dedupe by orderId once /v1/billing/redeem
                // ships; until then we accept the double-credit risk in V1.x.
                creditChipsFor(pack, result.transaction)
                billingClient.acknowledge(result.transaction.purchaseToken)
                IapPurchaseOutcome.AlreadyOwned(grantedChips = pack.grantsChips)
            }
            PurchaseResult.UserCancelled -> IapPurchaseOutcome.Cancelled
            is PurchaseResult.Failed -> IapPurchaseOutcome.Failed(result.reason)
            PurchaseResult.NotConnected -> IapPurchaseOutcome.StoreUnavailable
        }
        sendEvent(ShopEvent.PurchaseFinished(outcome))
    }

    private suspend fun creditChipsFor(pack: Product.ChipPack, transaction: PurchaseTransaction) {
        // V1 simplification: credit chips locally as soon as the platform
        // store confirms. Server-side receipt validation + chip ledger
        // (shop-roadmap §2) lands with the auth-gated `/v1/billing/redeem`
        // endpoint; until then this is the source of truth.
        chipsRepository.applyDelta(pack.grantsChips)
        logger.i { "Granted ${pack.grantsChips} chips for IAP order ${transaction.orderId}" }
    }

    private suspend fun confirmChipOfferRedeem(offer: Product.ChipOffer) {
        // Close the sheet first — feedback should be instant; the actual
        // optimistic write lands in the same frame on UnconfinedTestDispatcher
        // and a thread-or-two later in production.
        val action = ShopAction.ConfirmPendingPurchase
        action.updateState { it.copy(pendingPurchase = null) }

        val result = inventoryRepository.redeemChipOffer(
            productId = offer.id,
            costChips = offer.costChips,
        )
        when (result) {
            is RedeemResult.Success -> {
                sendEvent(ShopEvent.RedeemSucceeded(offer))
                // Fire-and-forget server reconcile so the Pending row flips
                // to Confirmed before the user closes the app. Failures
                // here are non-fatal — next launch retries.
                viewModelScope.launch { inventorySyncService.sync() }
            }
            is RedeemResult.InsufficientChips -> {
                // UI's affordance check should prevent this, but if a race
                // sneaks through (balance changed between sheet-open and
                // confirm) we surface it as an error toast.
                action.updateState {
                    it.copy(errorMessage = "Not enough chips for ${offer.title}.")
                }
            }
            is RedeemResult.AlreadyOwned -> {
                // Idempotent: tell the user it's already in their library
                // (without bothering them too much).
                sendEvent(ShopEvent.AlreadyOwned(offer))
            }
        }
    }
}

// ---------- MVI types ----------

data class ShopState(
    val catalog: ProductCatalog = ProductCatalog.Empty,
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val chipBalance: Long = ChipsRepository.STARTING_GRANT,
    val errorMessage: String? = null,
    val inventory: List<InventoryItem> = emptyList(),
    /** Quick-lookup set of product ids the user owns. Updated alongside
     *  [inventory] so screen code doesn't re-derive it on every recompose. */
    val ownedProductIds: Set<String> = emptySet(),
    /**
     * Player's current level, derived from lifetime XP via the cards
     * progression curve. Drives the unlock-state classifier for chip
     * offers gated by [Product.ChipOffer.unlockLevel].
     */
    val playerLevel: Int = 1,
    /**
     * Time anchor for the latest successful catalog fetch. Null until
     * the first fetch lands. Used by the UI to compute clock-spoof-
     * resistant remaining time for sale-window offers.
     */
    val timeAnchor: CatalogTimeAnchor? = null,
    /** Non-null while the purchase confirmation sheet is up. */
    val pendingPurchase: PendingPurchase? = null,
) {
    fun ownsProduct(productId: String): Boolean = productId in ownedProductIds

    /**
     * Can the user afford the offer? Used by the screen to disable the
     * row + by the VM as a final guard before mutating state.
     */
    fun canAfford(offer: Product.ChipOffer): Boolean = chipBalance >= offer.costChips

    /**
     * Is the product unlocked for this user (i.e., past the level gate)?
     * Products without an [Product.ChipOffer.unlockLevel] are always
     * unlocked. IAP packs don't have a level gate today.
     */
    fun isUnlocked(product: Product): Boolean = when (product) {
        is Product.ChipPack -> true // IAP packs aren't level-gated in V1.
        is Product.ChipOffer -> (product.unlockLevel ?: 1) <= playerLevel
    }

    /**
     * Has the sale window for [product] elapsed, according to the
     * effective server time? Returns false (not expired) when:
     *  - The product doesn't have a sale window (always available).
     *  - We don't have a time anchor yet (haven't fetched — assume good).
     *
     * NEVER trusts the device wall clock — always goes through
     * [CatalogTimeAnchor].
     */
    fun isExpired(product: Product): Boolean {
        val until = product.availableUntilEpochMs ?: return false
        val anchor = timeAnchor ?: return false
        return until <= anchor.effectiveServerNowMs()
    }

    /**
     * Classify a product for the purchase confirmation sheet. The sheet
     * opens for ALL states — Owned / Locked / Insufficient / Available
     * — but the rendered content + CTA varies. The shop screen used to
     * gate sheet-open at the action layer; we relaxed that so users can
     * still read "what is this thing?" even if they can't buy yet.
     *
     * Returns null for IAP packs (they always render as Available — no
     * level lock, no chip cost — for V1).
     */
    fun sheetModeFor(product: Product): PurchaseSheetMode = when {
        ownsProduct(product.id) -> {
            val pendingSync = inventory
                .firstOrNull { it.productId == product.id }
                ?.state == com.dangerfield.cards.libraries.cards.PurchaseState.Pending
            PurchaseSheetMode.Owned(pendingSync = pendingSync)
        }
        !isUnlocked(product) -> PurchaseSheetMode.Locked(
            requiredLevel = (product as? Product.ChipOffer)?.unlockLevel ?: 1,
        )
        product is Product.ChipOffer && !canAfford(product) -> PurchaseSheetMode.Insufficient(
            shortBy = (product.costChips - chipBalance).coerceAtLeast(0),
        )
        else -> PurchaseSheetMode.Available
    }

    /**
     * Classify a chip offer into one of the four visible states the
     * shop grid renders. Single source of truth; renderer is just
     * `when`. Priority: owned > locked > insufficient > available.
     */
    fun classify(offer: Product.ChipOffer): ChipOfferCardState = when {
        ownsProduct(offer.id) -> {
            val pendingSync = inventory
                .firstOrNull { it.productId == offer.id }
                ?.state == com.dangerfield.cards.libraries.cards.PurchaseState.Pending
            ChipOfferCardState.Owned(pendingSync = pendingSync)
        }
        !isUnlocked(offer) -> ChipOfferCardState.Locked(
            requiredLevel = offer.unlockLevel ?: 1,
        )
        !canAfford(offer) -> ChipOfferCardState.Insufficient(
            costChips = offer.costChips,
            shortBy = (offer.costChips - chipBalance).coerceAtLeast(0),
        )
        else -> ChipOfferCardState.Available(costChips = offer.costChips)
    }
}

/**
 * The mutually-exclusive states the purchase confirmation sheet renders
 * in. Mirrors [ChipOfferCardState] but lives in its own type so the
 * sheet can also represent owned items (the grid card has a different
 * visual for owned and doesn't share this state).
 *
 * Classifier: [ShopState.sheetModeFor]. Visual rendering:
 * [PurchaseConfirmSheet].
 */
sealed interface PurchaseSheetMode {
    /** Buyable: user is unlocked + can afford + offer is in-window. */
    data object Available : PurchaseSheetMode

    /** Unlocked but can't afford. Disabled CTA + "Need X more chips" copy. */
    data class Insufficient(val shortBy: Long) : PurchaseSheetMode

    /** Locked by level. Disabled CTA + "Unlocks at Level N" copy. */
    data class Locked(val requiredLevel: Int) : PurchaseSheetMode

    /** User already owns this. No buy CTA — close + "manage in profile" hint. */
    data class Owned(val pendingSync: Boolean) : PurchaseSheetMode
}

/**
 * The mutually-exclusive states a chip-offer card can be in on the shop
 * grid. The renderer dispatches on this — each variant carries exactly
 * the data its visual treatment needs.
 *
 * Defined as a sealed interface so adding a future state (e.g.,
 * "TimeLimited", "RequiresAchievement") only touches the classifier and
 * the renderer, not every call site.
 */
sealed interface ChipOfferCardState {
    /** Buyable: user is unlocked and can afford. */
    data class Available(val costChips: Long) : ChipOfferCardState

    /** Unlocked but can't afford. Show cost in danger + deficit indicator. */
    data class Insufficient(val costChips: Long, val shortBy: Long) : ChipOfferCardState

    /** Locked by level. Show lock overlay + "Unlocks at Level N" footer. */
    data class Locked(val requiredLevel: Int) : ChipOfferCardState

    /** User already owns this. [pendingSync] true while the local row
     *  hasn't been confirmed by the server yet. */
    data class Owned(val pendingSync: Boolean) : ChipOfferCardState
}

/**
 * One in-flight purchase, kept on state so the bottom sheet renders.
 *
 * Two variants because the flow downstream is different: IAP packs need
 * the platform billing system to confirm, chip offers complete locally and
 * optimistically.
 */
sealed interface PendingPurchase {
    data class IapPack(val product: Product.ChipPack) : PendingPurchase
    data class ChipOffer(val product: Product.ChipOffer) : PendingPurchase

    companion object {
        fun from(product: Product): PendingPurchase = when (product) {
            is Product.ChipPack -> IapPack(product)
            is Product.ChipOffer -> ChipOffer(product)
        }
    }
}

sealed interface ShopAction {
    /** Pull-to-refresh / initial load. */
    data object Refresh : ShopAction
    data object RefreshFinished : ShopAction
    data class RefreshFailed(val message: String) : ShopAction
    data class CatalogChanged(val catalog: ProductCatalog) : ShopAction
    data class ChipsChanged(val balance: Long) : ShopAction
    data class InventoryChanged(val inventory: List<InventoryItem>) : ShopAction
    data class PlayerLevelChanged(val level: Int) : ShopAction
    data class TimeAnchorChanged(val anchor: CatalogTimeAnchor?) : ShopAction
    data object DismissError : ShopAction

    /** User tapped a product row → open the purchase confirmation sheet. */
    data class RequestPurchase(val product: Product) : ShopAction

    /** User tapped the confirm CTA inside the sheet → commit. */
    data object ConfirmPendingPurchase : ShopAction

    /** User dismissed the sheet (cancel, swipe-down, back press, tap outside). */
    data object DismissPendingPurchase : ShopAction
}

sealed interface ShopEvent {
    /**
     * IAP purchase round-trip has completed (success, cancel, failure).
     * Screen renders a toast / celebration based on [outcome]. The
     * billing client has already been driven to completion before this
     * event fires — the screen doesn't have to call any further APIs.
     */
    data class PurchaseFinished(val outcome: IapPurchaseOutcome) : ShopEvent

    /** Chip-funded redemption confirmed. Screen plays a celebration cue. */
    data class RedeemSucceeded(val offer: Product.ChipOffer) : ShopEvent

    /** Idempotent re-redeem — user tried to buy something they already own. */
    data class AlreadyOwned(val offer: Product.ChipOffer) : ShopEvent

    /**
     * Tap on an offer whose sale window expired between the catalog
     * fetch and the user's tap (or whose countdown reached zero mid-
     * scroll). The screen surfaces a soft toast and the VM auto-fires
     * a refresh, after which the offer drops out of the catalog.
     */
    data class OfferExpired(val productId: String) : ShopEvent
}

/**
 * Result of an IAP purchase, emitted as [ShopEvent.PurchaseFinished]
 * once the billing round-trip + chip credit completes.
 *
 * The screen dispatches on this to render the right user feedback — the
 * VM doesn't decide on copy or animation. Cancellation is silent (no
 * toast); failure is a single "couldn't complete" line; success is a
 * chip-pile celebration with the granted amount.
 */
sealed interface IapPurchaseOutcome {
    data class Success(val grantedChips: Long) : IapPurchaseOutcome

    /** Store reports already-owned. We re-credit defensively. */
    data class AlreadyOwned(val grantedChips: Long) : IapPurchaseOutcome

    /** User backed out of the store sheet. Silent. */
    data object Cancelled : IapPurchaseOutcome

    /** Platform billing connection isn't established. Surface "store unavailable". */
    data object StoreUnavailable : IapPurchaseOutcome

    /** No signed-in user. Should be impossible from the shop screen, but defend. */
    data object NotSignedIn : IapPurchaseOutcome

    /** Generic transient error. [reason] is store-provided and not localized. */
    data class Failed(val reason: String) : IapPurchaseOutcome
}
