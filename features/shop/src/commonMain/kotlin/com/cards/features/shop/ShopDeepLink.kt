package com.dangerfield.cards.features.shop

import kotlinx.coroutines.flow.Flow

/**
 * The storefront shelves the shop grid groups products into. A
 * cross-tab deep-link can request the grid scroll to one of these on
 * entry (e.g. Edit Profile's "Get more avatar packs" link → [Avatars]).
 *
 * Mirrors the private rendering grouping in `ShopScreen` (keyed off the
 * product-id prefix convention). Lives in the api so the *initiator* of
 * a deep-link (a different feature) can name the target without reaching
 * into the shop impl.
 */
enum class ShopCategory {
    CardBacks,
    Felts,
    Tables,
    Emotes,
    Avatars,
    Tools,
}

/**
 * One-way signal asking the shop grid to scroll to a [ShopCategory] the
 * next time it's shown. The mechanism the routing rules force: the Shop
 * tab root ([ShopRoute]) is arg-less (tab-root args get clobbered by
 * `restoreState`), so a "land on the avatars section" intent can't ride
 * a route field — it rides this app-scoped one-shot the `ShopViewModel`
 * consumes, the same shape [ShopProductSheetRoute] solves for "open this
 * product's sheet."
 *
 * Conflated + consume-once: a request fired before the grid exists (a
 * cold deep-link) is held until the VM subscribes; a later unrelated
 * shop visit doesn't replay a stale scroll. See `docs/decisions.md`.
 */
interface ShopDeepLinkBus {

    /** Fire-and-forget — called alongside `switchTab(ShopGraph)`. */
    fun requestScrollTo(category: ShopCategory)

    /** Observed by the shop grid's VM to drive the one-shot scroll. */
    val scrollRequests: Flow<ShopCategory>
}
