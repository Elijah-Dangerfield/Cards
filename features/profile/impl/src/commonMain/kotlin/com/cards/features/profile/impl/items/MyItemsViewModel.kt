package com.dangerfield.cards.features.profile.impl.items

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AcquisitionSource
import com.dangerfield.cards.libraries.cards.CosmeticTier
import com.dangerfield.cards.libraries.cards.EmotePackCatalog
import com.dangerfield.cards.libraries.cards.EquipmentEntry
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.cosmeticSlotFor
import com.dangerfield.cards.libraries.cards.isDefaultCosmetic
import com.dangerfield.cards.libraries.cards.tierForProductId
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.profile.AvatarPack
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.ProductsRepository
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

/**
 * Drives the My Items screen. Joins three live sources:
 *  - inventory (what the user owns)
 *  - catalog (display metadata: title, description, emoji)
 *  - equipment (which of the owned items are equipped + sync state)
 *
 * Optimistic toggles: tapping equip/unequip writes Pending to the local
 * Room table immediately (the flow re-emits, UI re-renders without a
 * round-trip), then fires [EquipmentRepository.sync] as a best-effort
 * background reconcile. If the user is offline, the row stays Pending
 * and the next bootstrap cycle picks it up.
 *
 * Catalog refresh is kicked at init so visually-stale tiles get the
 * latest server descriptions on entry. Equipment sync is kicked too in
 * case the bootstrapper hasn't fired this session (e.g. the user
 * navigated here from a deep link before any AppEvent landed).
 *
 * Items that the user owns but for which the catalog dropped the entry
 * (server pulled the product down after purchase) still render with
 * fallback copy — ownership is permanent regardless of catalog drift.
 */
@Inject
class MyItemsViewModel(
    private val inventoryRepository: InventoryRepository,
    private val productsRepository: ProductsRepository,
    private val equipmentRepository: EquipmentRepository,
    private val profileRepository: ProfileRepository,
) : SEAViewModel<MyItemsState, MyItemsEvent, MyItemsAction>(initialStateArg = MyItemsState()) {

    private val logger = KLog.withTag("MyItemsViewModel")

    init {
        viewModelScope.launch {
            inventoryRepository.observeInventory().collect { items ->
                takeAction(MyItemsAction.InventoryChanged(items))
            }
        }
        viewModelScope.launch {
            productsRepository.observeCatalog().collect { catalog ->
                takeAction(MyItemsAction.CatalogChanged(catalog))
            }
        }
        viewModelScope.launch {
            equipmentRepository.observeEquipped().collect { entries ->
                takeAction(MyItemsAction.EquipmentChanged(entries))
            }
        }
        // Pack contents (avatar packs) so the bookshelf can render a pack's
        // emojis + the detail sheet's "In this pack" grid. Best-effort: a
        // failure just leaves avatar packs rendering their fallback glyph.
        viewModelScope.launch {
            Catching { profileRepository.fetchAvatarPack() }
                .getOrNull()
                ?.let { outcome ->
                    if (outcome is AvatarPackOutcome.Success) {
                        takeAction(MyItemsAction.AvatarPacksLoaded(outcome.packs))
                    }
                }
        }
        // Best-effort fetches on entry. Both gracefully no-op when offline.
        viewModelScope.launch { productsRepository.refresh() }
        viewModelScope.launch { equipmentRepository.sync() }
    }

    override suspend fun handleAction(action: MyItemsAction) {
        when (action) {
            is MyItemsAction.InventoryChanged -> action.updateState {
                it.copy(inventory = action.items)
            }
            is MyItemsAction.CatalogChanged -> action.updateState {
                it.copy(catalog = action.catalog)
            }
            is MyItemsAction.AvatarPacksLoaded -> action.updateState {
                it.copy(avatarPacks = action.packs)
            }
            is MyItemsAction.EquipmentChanged -> action.updateState {
                it.copy(
                    equippedIds = action.entries
                        .filter { entry -> entry.isEquipped }
                        .map { entry -> entry.productId }
                        .toSet(),
                )
            }

            is MyItemsAction.ToggleEquipped -> action.run {
                val currentlyEquipped = action.productId in state.equippedIds
                // Optimistic: the equipment flow re-emits within the same
                // frame so we never need to manually flip UI state here.
                if (currentlyEquipped) {
                    equipmentRepository.unequip(action.productId)
                } else {
                    // Single-equip per slot: if the user picks a new felt /
                    // card back / title, retire any previously-equipped item
                    // in the same slot first. The rendering layer used to
                    // pick the first-equipped one, so leaving both equipped
                    // meant the new pick wouldn't take effect.
                    val slot = cosmeticSlotFor(action.productId)
                    if (slot != null) {
                        state.equippedIds
                            .filter { it != action.productId && cosmeticSlotFor(it) == slot }
                            .forEach { equipmentRepository.unequip(it) }
                    }
                    equipmentRepository.equip(action.productId)
                    logger.logEvent(
                        "cosmetic.equipped",
                        "product_id" to action.productId,
                        "slot" to slot?.name?.lowercase(),
                        "auto" to false,
                    )
                }
                // Fire-and-forget reconcile so Pending → Synced flips
                // before the user even sees the row settle.
                viewModelScope.launch {
                    equipmentRepository.sync().onFailure {
                        logger.w(it) { "Sync after toggle failed; row stays Pending until next launch." }
                    }
                }
            }
        }
    }
}

/**
 * One row on the My Items list. Derived from the inventory ∩ catalog join
 * inside [MyItemsState.ownedItems]. If the catalog dropped the product
 * after purchase, fields fall back to friendly placeholders so the user
 * isn't confronted with a blank row.
 */
data class OwnedItem(
    val productId: String,
    val title: String,
    val subtitle: String,
    val description: String?,
    val iconEmoji: String,
    val isEquipped: Boolean,
    /**
     * Mirrored from the catalog `Product.isEquippable`. False for unlock-
     * style products (avatar packs, emote packs) — the row still renders,
     * but the Equip/Unequip button is suppressed. Falls back to false
     * when the catalog entry is missing so a stale-catalog row doesn't
     * silently show an Equip button that doesn't do anything.
     */
    val isEquippable: Boolean,
    /**
     * Server-driven provenance. Drives the "Earned" affordance on the row
     * for cosmetics granted by an achievement / league / RFT path rather
     * than a chip purchase. Defaults to [AcquisitionSource.Purchased] so a
     * row built from a pre-V13 inventory snapshot doesn't accidentally
     * claim earned-prestige.
     */
    val acquisitionSource: AcquisitionSource = AcquisitionSource.Purchased,
    /**
     * Catalog-axis tier for the underlying product, looked up via
     * [tierForProductId]. `null` for products that have no
     * achievement-grant mapping (i.e. shop-only / `BUY_ONLY`); non-null
     * for everything in the [EarnableCosmetics] map.
     *
     * Lets the row distinguish an [EARN_ONLY] earned item (single source,
     * "Earned" pin always applies) from an [EARN_OR_BUY] earned item (the
     * same product could also have been bought, so an achievement badge
     * reinforces the earn-grant variant). See `docs/todo.md` §A "Catalog
     * gating".
     */
    val tier: CosmeticTier? = null,
    /** Wall-clock at acquisition, from [InventoryItem.purchasedAtEpochMs].
     *  Feeds the detail sheet's "Earned/Bought … ago" line. */
    val acquiredAtEpochMs: Long = 0L,
    /** Chip cost paid at purchase ([InventoryItem.costChipsAtPurchase]); 0
     *  for IAP and earned grants. Feeds the detail sheet's price line. */
    val costChipsAtPurchase: Long = 0L,
    /**
     * For "pack" products (avatar packs, emote packs) — the emojis the pack
     * bundles. Empty for single cosmetics. Drives the overlapping-emoji pack
     * thumbnail on the shelf and the "In this pack" grid in the detail sheet.
     */
    val packEmojis: List<String> = emptyList(),
)

/**
 * A not-yet-owned cosmetic the user could buy, surfaced as a dimmed tile
 * after the owned items on a shoppable shelf (card backs, felts, avatar /
 * emote packs). Tapping routes to the shop. Price is intentionally omitted —
 * the tile is a "there's more in the shop" nudge, not a purchase surface.
 */
data class BuyableCosmetic(
    val productId: String,
    val title: String,
    val iconEmoji: String,
    val packEmojis: List<String> = emptyList(),
)

/**
 * Inventory / catalog product id for the always-granted starter avatar pack.
 * Mirrors the server's `StarterInventory` grant; the matching wire pack has a
 * null `unlockProductId` rather than this id.
 */
private const val STARTER_AVATAR_PACK_PRODUCT_ID = "avatars_starter"

data class MyItemsState(
    val inventory: List<InventoryItem> = emptyList(),
    val catalog: ProductCatalog = ProductCatalog.Empty,
    val equippedIds: Set<String> = emptySet(),
    val avatarPacks: List<AvatarPack> = emptyList(),
) {
    /** Owned items, newest purchase first, with catalog metadata folded in. */
    val ownedItems: List<OwnedItem>
        get() {
            // Prestige grants (badges + titles) ride a separate catalog bucket —
            // the shop never sells them — but they carry the same display
            // metadata, so fold them in here to give an owned badge/title its
            // real name, emoji, and description instead of a bare "🎁".
            val productsById = (catalog.chipOffers + catalog.chipPacks + catalog.prestige)
                .associateBy { it.id }
            // Slots whose default cosmetic should render as equipped: a slot
            // owns its default implicitly whenever nothing else in that slot
            // carries an explicit equipment row. Defaults are seeded into
            // inventory without an equipment row, so without this they'd never
            // show the equipped badge.
            val slotsWithExplicitEquip = equippedIds
                .mapNotNull { cosmeticSlotFor(it) }
                .toSet()
            return inventory.map { item ->
                val product = productsById[item.productId]
                val isDefaultEquipped = isDefaultCosmetic(item.productId) &&
                    cosmeticSlotFor(item.productId)?.let { it !in slotsWithExplicitEquip } == true
                OwnedItem(
                    productId = item.productId,
                    title = product?.title ?: prettifyMissingId(item.productId),
                    subtitle = product?.subtitle ?: "Owned item",
                    description = (product as? Product.ChipOffer)?.description,
                    iconEmoji = product?.iconEmoji ?: "🎁",
                    isEquipped = item.productId in equippedIds || isDefaultEquipped,
                    isEquippable = product?.isEquippable ?: false,
                    acquisitionSource = item.acquisitionSource,
                    tier = tierForProductId(item.productId),
                    acquiredAtEpochMs = item.purchasedAtEpochMs,
                    costChipsAtPurchase = item.costChipsAtPurchase,
                    packEmojis = packEmojisFor(item.productId),
                )
            }
        }

    /**
     * Catalog cosmetics the user doesn't own yet, as dimmed "buy me" tiles
     * the shelves render after the owned items. Drawn from chip-offers (the
     * cosmetic side of the catalog) minus everything already in inventory.
     * The shelves themselves decide which categories get a buyable fill.
     */
    val buyableItems: List<BuyableCosmetic>
        get() {
            val ownedIds = inventory.map { it.productId }.toSet()
            return catalog.chipOffers
                .filter { it.id !in ownedIds }
                .map { offer ->
                    BuyableCosmetic(
                        productId = offer.id,
                        title = offer.title,
                        iconEmoji = offer.iconEmoji,
                        packEmojis = packEmojisFor(offer.id),
                    )
                }
        }

    /**
     * The emojis a "pack" product bundles, for the overlapping-emoji
     * thumbnail + detail grid. Emote packs read from the shared
     * [EmotePackCatalog]; avatar packs from the server-fetched [avatarPacks].
     * Empty for single cosmetics (felts, card backs, titles, tools, badges).
     */
    private fun packEmojisFor(productId: String): List<String> = when {
        productId.startsWith("emotes_") -> EmotePackCatalog.emojisForPack(productId)
        productId.startsWith("avatars_") -> avatarPackFor(productId)?.emojis.orEmpty()
        else -> emptyList()
    }

    /**
     * Resolve an avatar **product id** (the inventory / catalog id, e.g.
     * `avatars_animals` or `avatars_starter`) to its server [AvatarPack].
     * The wire packs key off their own ids (`animals`, `starter`) and link
     * back via [AvatarPack.unlockProductId], so matching `pack.id == productId`
     * never hits — premium packs match on `unlockProductId`, and the starter
     * pack (inventory id `avatars_starter`, no unlock product) is the single
     * always-granted pack with a null `unlockProductId`.
     */
    private fun avatarPackFor(productId: String): AvatarPack? =
        if (productId == STARTER_AVATAR_PACK_PRODUCT_ID) {
            avatarPacks.firstOrNull { it.unlockProductId == null }
        } else {
            avatarPacks.firstOrNull { it.unlockProductId == productId }
        }

    private fun prettifyMissingId(productId: String): String =
        productId.substringAfterLast('.', productId)
            .replace('_', ' ')
            .replaceFirstChar { it.titlecase() }
}

sealed interface MyItemsEvent

sealed interface MyItemsAction {
    data class InventoryChanged(val items: List<InventoryItem>) : MyItemsAction
    data class CatalogChanged(val catalog: ProductCatalog) : MyItemsAction
    data class AvatarPacksLoaded(val packs: List<AvatarPack>) : MyItemsAction
    data class EquipmentChanged(val entries: List<EquipmentEntry>) : MyItemsAction
    data class ToggleEquipped(val productId: String) : MyItemsAction
}
