package com.dangerfield.cards.features.shop.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.features.shop.ShopCategory
import com.dangerfield.cards.features.shop.ShopDeepLinkBus
import com.dangerfield.cards.libraries.billing.BillingClient
import com.dangerfield.cards.libraries.billing.PurchaseResult
import com.dangerfield.cards.libraries.billing.PurchaseTransaction
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.RedeemResult
import com.dangerfield.cards.libraries.cards.cosmeticSlotFor
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
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
 * Optimistic redemption flow ([ShopAction.ConfirmPurchase] for a
 * chip-funded offer):
 *  1. Pre-check chip balance — if insufficient, surface error without
 *     mutating anything. (UI also disables the button when balance &lt;
 *     cost so this path is defensive, not primary.)
 *  2. Call [InventoryRepository.redeemChipOffer] — it atomically
 *     inserts the inventory row as Pending and deducts chips.
 *  3. Sheet is already popping (navigation), VM emits a confirmation
 *     event for the UI to celebrate (toast, haptic, whatever).
 *  4. Best-effort fire [InventoryRepository.sync] in the background so
 *     the row flips Pending → Confirmed before the user closes the app.
 *
 * IAP packs ([ShopAction.ConfirmPurchase] for a chip pack) drive
 * [BillingClient.purchase] and emit a [ShopEvent.PurchaseFinished]
 * once the store sheet resolves (success, cancel, failure). Chips are
 * credited locally via [ChipsRepository.addChips] when the store
 * confirms — server-side receipt validation is deferred.
 */
class ShopViewModel @Inject constructor(
    private val productsRepository: ProductsRepository,
    private val inventoryRepository: InventoryRepository,
    private val chipsRepository: ChipsRepository,
    private val progressionRepository: ProgressionRepository,
    private val billingClient: BillingClient,
    private val authRepository: AuthRepository,
    private val equipmentRepository: EquipmentRepository,
    private val deepLinkBus: ShopDeepLinkBus,
) : SEAViewModel<ShopState, ShopEvent, ShopAction>(initialStateArg = ShopState()) {

    private val logger = KLog.withTag("ShopViewModel")

    init {
        viewModelScope.launch {
            productsRepository.observeCatalog().collect { catalog ->
                takeAction(ShopAction.CatalogChanged(catalog))
            }
        }
        viewModelScope.launch {
            // Mirror the repo's refresh-in-flight signal so the
            // loading spinner shows for repo-driven refreshes (cold
            // boot, session rollover) without the VM having to know
            // *which* call started them.
            productsRepository.observeIsRefreshing().collect { refreshing ->
                takeAction(ShopAction.RefreshingChanged(refreshing))
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
        viewModelScope.launch {
            // A cross-tab deep-link (e.g. Edit Profile's "Get more avatar
            // packs") asks the grid to land on a category section. The
            // bus is conflate-then-consume so a request fired before the
            // grid existed still lands, and the screen drives the actual
            // scroll once the section is measured.
            deepLinkBus.scrollRequests.collect { category ->
                takeAction(ShopAction.ScrollToCategory(category))
            }
        }
        // No explicit catalog refresh on screen entry: the repository
        // is session-aware and self-triggers on cold boot + on any
        // background-rollover (see ProductsRepositoryImpl). VM `init`
        // would otherwise fire a redundant non-forced refresh that
        // the repository would short-circuit anyway. Pull-to-refresh
        // (ShopAction.Refresh with force = true) is the only path
        // that still goes through the VM for the catalog.
        //
        // Inventory still syncs on every screen entry — it changes
        // with gameplay between shop visits and is cheap.
        viewModelScope.launch { inventoryRepository.sync() }
    }

    override suspend fun handleAction(action: ShopAction) {
        when (action) {
            is ShopAction.Refresh -> {
                // Errors are cleared optimistically — a fresh user pull
                // dismisses the prior banner immediately. isRefreshing
                // is driven by the repo flow (observeIsRefreshing)
                // rather than set here, so any concurrently in-flight
                // session-rollover refresh stays correctly reflected.
                action.updateState { it.copy(errorMessage = null) }
                viewModelScope.launch {
                    val result = productsRepository.refresh(force = action.force)
                    result.onFailure { failure ->
                        logger.w(failure) { "Catalog refresh failed" }
                        takeAction(ShopAction.RefreshFailed(failure.message ?: "Couldn't load shop"))
                    }
                }
            }
            is ShopAction.RefreshingChanged -> action.updateState {
                // `hasLoaded` flips true the first time we see a refresh
                // *finish* (transition true → false). Until that point,
                // the screen renders the loading spinner regardless of
                // whether the catalog is empty (could be a fresh
                // install fetching for the first time).
                val hasLoaded = it.hasLoaded || (it.isRefreshing && !action.value)
                it.copy(isRefreshing = action.value, hasLoaded = hasLoaded)
            }
            is ShopAction.RefreshFailed -> action.updateState {
                it.copy(errorMessage = action.message)
            }
            is ShopAction.CatalogChanged -> action.updateState {
                // Disk-hydrated catalog or a successful refresh both
                // count as "loaded" — once we have any non-empty
                // catalog the screen can render the grid even if a
                // refresh is mid-flight.
                val hasLoaded = it.hasLoaded || !action.catalog.isEmpty
                it.copy(catalog = action.catalog, hasLoaded = hasLoaded)
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
            is ShopAction.ScrollToCategory -> action.updateState {
                it.copy(pendingScrollCategory = action.category)
            }
            is ShopAction.ScrollConsumed -> action.updateState {
                it.copy(pendingScrollCategory = null)
            }

            // ---- Purchase confirm flow ----
            //
            // The purchase confirmation sheet is its own navigation
            // route (`ShopProductSheetRoute`); opening and dismissing
            // it are pure navigation operations, not VM actions.
            // The VM only handles the *confirm* leg (commit the
            // purchase) since that's what depends on the
            // BillingClient / inventory repository.

            is ShopAction.ConfirmPurchase -> {
                // Defense-in-depth: the sheet's primary CTA is
                // disabled for non-buyable states, but the action
                // layer is the final fence. Anything outside
                // Available is a no-op.
                val product = action.product
                if (state.sheetModeFor(product) !is PurchaseSheetMode.Available) return
                if (state.isExpired(product)) {
                    sendEvent(ShopEvent.OfferExpired(product.id))
                    takeAction(ShopAction.Refresh(force = true))
                    return
                }
                when (product) {
                    is Product.ChipPack -> launchIapPurchase(product)
                    is Product.ChipOffer -> confirmChipOfferRedeem(product)
                }
            }
        }
    }

    private suspend fun launchIapPurchase(pack: Product.ChipPack) {
        val authenticated = authRepository.current() as? AuthState.Authenticated
        if (authenticated == null) {
            logger.w { "IAP purchase requested with no signed-in user" }
            sendEvent(ShopEvent.PurchaseFinished(IapPurchaseOutcome.NotSignedIn))
            return
        }
        if (authenticated.isAnonymous) {
            // Real-money IAP is gated behind account claim: an anonymous
            // user can't buy until they've linked email/Apple, removing the
            // "paid then lost the account" risk at the source. Route to the
            // claim flow instead of the platform purchase sheet.
            logger.i { "IAP purchase blocked for anonymous user — routing to account claim" }
            sendEvent(ShopEvent.ClaimAccountRequired)
            return
        }
        val userId = authenticated.userId
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
        // store confirms. Server-side receipt validation + chip ledger lands
        // with the auth-gated `/v1/billing/redeem` endpoint; until then this
        // is the source of truth. The order id
        // doubles as the idempotency key so a duplicate purchase-confirmed
        // signal (e.g. resume-after-restore) doesn't double-credit when
        // the wallet sync flushes either copy of the event.
        chipsRepository.addChips(
            amount = pack.grantsChips,
            reason = "iap.${pack.id}",
            idempotencyKey = "iap.${pack.id}.${transaction.orderId}",
        )
        logger.i { "Granted ${pack.grantsChips} chips for IAP order ${transaction.orderId}" }
    }

    private suspend fun confirmChipOfferRedeem(offer: Product.ChipOffer) {
        // Sheet route is popped synchronously by the caller (sheet
        // composable's confirm handler runs `router.goBack()` before
        // dispatching this action), so the sheet is already closing by
        // the time we land here. No state mutation needed here.
        val action = ShopAction.ConfirmPurchase(offer)

        val result = inventoryRepository.redeemChipOffer(
            productId = offer.id,
            costChips = offer.costChips,
        )
        when (result) {
            is RedeemResult.Success -> {
                val autoEquipped = autoEquipIfSlotFree(offer.id)
                sendEvent(ShopEvent.RedeemSucceeded(offer, wasAutoEquipped = autoEquipped))
                // Fire-and-forget server reconcile so the Pending row flips
                // to Confirmed before the user closes the app. Failures
                // here are non-fatal — next launch retries.
                viewModelScope.launch { inventoryRepository.sync() }
            }
            is RedeemResult.InsufficientChips -> {
                // UI's affordance check should prevent this, but if a race
                // sneaks through (balance changed between sheet-open and
                // confirm) we surface it as a transient error snackbar — not
                // the persistent `errorMessage` banner, which is reserved for
                // screen-level state like the offline-cache notice.
                sendEvent(ShopEvent.InsufficientChips(offer))
            }
            is RedeemResult.AlreadyOwned -> {
                // Idempotent: tell the user it's already in their library
                // (without bothering them too much).
                sendEvent(ShopEvent.AlreadyOwned(offer))
            }
        }
    }

    /**
     * Equips [productId] iff it's a slot cosmetic whose slot is currently
     * free. Returns true when it actually equipped — the caller uses this
     * to tell the success toast "we equipped this for you" (with a "My
     * items" button) instead of offering an "Equip" action that would be a
     * no-op.
     */
    private suspend fun autoEquipIfSlotFree(productId: String): Boolean {
        // Skip non-slot products (avatar packs, emote packs, anything the
        // helper doesn't classify) — those don't have a "currently
        // equipped" notion and equipping them on purchase doesn't change
        // a render-layer pick.
        val slot = cosmeticSlotFor(productId) ?: return false
        // Honor any cosmetic the user has *already* picked in this slot;
        // we don't want a fresh purchase to silently steal an in-use felt
        // / card back. The user can flip later from My Items.
        val occupied = equipmentRepository.getAll()
            .any { entry -> entry.isEquipped && cosmeticSlotFor(entry.productId) == slot }
        if (occupied) return false
        equipmentRepository.equip(productId)
        viewModelScope.launch { equipmentRepository.sync() }
        return true
    }
}

// ---------- MVI types ----------

data class ShopState(
    val catalog: ProductCatalog = ProductCatalog.Empty,
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    /** `null` while the first chip sync hasn't hydrated the local row.
     *  Affordance gates ([canAfford], [classify], [sheetModeFor]) treat
     *  null as "can't afford anything" so the buy CTA stays disabled. */
    val chipBalance: Long? = null,
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
    /**
     * Set when a deep-link asks the grid to scroll to a category section
     * (e.g. "Get more avatar packs" → [ShopCategory.Avatars]). The screen
     * scrolls to the section once it's measured, then fires
     * [ShopAction.ScrollConsumed] to clear this so a recompose doesn't
     * re-trigger the scroll.
     */
    val pendingScrollCategory: ShopCategory? = null,
) {
    fun ownsProduct(productId: String): Boolean = productId in ownedProductIds

    /**
     * Can the user afford the offer? Used by the screen to disable the
     * row + by the VM as a final guard before mutating state.
     */
    fun canAfford(offer: Product.ChipOffer): Boolean =
        chipBalance != null && chipBalance >= offer.costChips

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
        ownsProduct(product.id) -> PurchaseSheetMode.Owned
        !isUnlocked(product) -> PurchaseSheetMode.Locked(
            requiredLevel = (product as? Product.ChipOffer)?.unlockLevel ?: 1,
        )
        product is Product.ChipOffer && !canAfford(product) -> PurchaseSheetMode.Insufficient(
            shortBy = (product.costChips - (chipBalance ?: 0L)).coerceAtLeast(0),
        )
        else -> PurchaseSheetMode.Available
    }

    /**
     * Classify a chip offer into one of the four visible states the
     * shop grid renders. Single source of truth; renderer is just
     * `when`. Priority: owned > locked > insufficient > available.
     */
    fun classify(offer: Product.ChipOffer): ChipOfferCardState = when {
        ownsProduct(offer.id) -> ChipOfferCardState.Owned
        !isUnlocked(offer) -> ChipOfferCardState.Locked(
            requiredLevel = offer.unlockLevel ?: 1,
        )
        !canAfford(offer) -> ChipOfferCardState.Insufficient(
            costChips = offer.costChips,
            shortBy = (offer.costChips - (chipBalance ?: 0L)).coerceAtLeast(0),
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
    data object Owned : PurchaseSheetMode
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

    /** User already owns this. */
    data object Owned : ChipOfferCardState
}

sealed interface ShopAction {
    /**
     * Initial load + pull-to-refresh. [force] = true on pull-to-refresh
     * (and other "user clearly wants fresh" triggers like an expired
     * offer tap); false on screen-entry so the repository's freshness
     * window can short-circuit duplicate fetches.
     */
    data class Refresh(val force: Boolean) : ShopAction
    /**
     * Mirrors the repository's [com.dangerfield.cards.libraries.products.ProductsRepository.observeIsRefreshing]
     * so the loading state stays accurate whether the refresh was
     * pull-driven or session-rollover-driven.
     */
    data class RefreshingChanged(val value: Boolean) : ShopAction
    data class RefreshFailed(val message: String) : ShopAction
    data class CatalogChanged(val catalog: ProductCatalog) : ShopAction
    data class ChipsChanged(val balance: Long?) : ShopAction
    data class InventoryChanged(val inventory: List<InventoryItem>) : ShopAction
    data class PlayerLevelChanged(val level: Int) : ShopAction
    data class TimeAnchorChanged(val anchor: CatalogTimeAnchor?) : ShopAction
    data object DismissError : ShopAction

    /**
     * A deep-link requested the grid scroll to [category]'s section. Held
     * in state until the screen measures the section and scrolls; cleared
     * by [ScrollConsumed].
     */
    data class ScrollToCategory(val category: ShopCategory) : ShopAction

    /** The screen finished the deep-link scroll — clear the pending target. */
    data object ScrollConsumed : ShopAction

    /**
     * Confirm the purchase of [product] from inside the sheet. Opening
     * and dismissing the sheet are navigation operations
     * (`ShopProductSheetRoute`), not actions — only the commit step
     * runs through the VM.
     */
    data class ConfirmPurchase(val product: Product) : ShopAction
}

sealed interface ShopEvent {
    /**
     * IAP purchase round-trip has completed (success, cancel, failure).
     * Screen renders a toast / celebration based on [outcome]. The
     * billing client has already been driven to completion before this
     * event fires — the screen doesn't have to call any further APIs.
     */
    data class PurchaseFinished(val outcome: IapPurchaseOutcome) : ShopEvent

    /**
     * Chip-funded redemption confirmed. Screen plays a celebration cue.
     *
     * [wasAutoEquipped] is true when the purchase landed in a free cosmetic
     * slot and we equipped it right away. The toast uses this to swap its
     * action: an offer-an-"Equip" button when the user still needs to equip
     * it, vs. a plain "My items" button (and "already equipped" copy) when
     * there's nothing left to do.
     */
    data class RedeemSucceeded(
        val offer: Product.ChipOffer,
        val wasAutoEquipped: Boolean,
    ) : ShopEvent

    /**
     * An anonymous user tapped buy on a real-money pack. The screen shows an
     * error snackbar with a "Create account" action that routes to the claim
     * flow — they must link an account before any real purchase, but we
     * explain why rather than yanking them into onboarding unprompted.
     */
    data object ClaimAccountRequired : ShopEvent

    /** Idempotent re-redeem — user tried to buy something they already own. */
    data class AlreadyOwned(val offer: Product.ChipOffer) : ShopEvent

    /**
     * Redeem hit the wallet floor — the balance changed between sheet-open
     * and confirm (the buyable-state check normally prevents this). The
     * screen surfaces a transient error snackbar.
     */
    data class InsufficientChips(val offer: Product.ChipOffer) : ShopEvent

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
