package com.dangerfield.cards.features.shop

import com.dangerfield.cards.libraries.navigation.Route
import com.dangerfield.cards.libraries.navigation.TabRoute
import kotlinx.serialization.Serializable

/**
 * Shop tab root — the grid surface. Arg-less by design.
 *
 * The "open the purchase sheet for product X" intent belongs on
 * [ShopProductSheetRoute], a sub-route of this tab, **not** on a field
 * here. Tab roots can't carry one-shot args because the bottom-bar
 * tab-switch recipe (`saveState` + `restoreState`) restores the saved
 * entry verbatim — new args you pass on a fresh `switchTab` are
 * silently dropped. See `docs/decisions.md` ("Tab roots are arg-less").
 */
@Serializable
class ShopRoute : TabRoute()

/**
 * Nested-graph identifier for the Shop tab. Wraps [ShopRoute] (the
 * grid, also the tab's start destination) and [ShopProductSheetRoute]
 * (the purchase sheet sub-route) so they can share a single
 * `ShopViewModel` scoped to this graph entry — `router.graphScopedViewModel<ShopGraph, ShopViewModel>()`.
 *
 * Not a navigable destination on its own; only used as the graph
 * identifier passed to `NavGraphBuilder.navigation<ShopGraph>(...)`.
 */
@Serializable
data object ShopGraph : Route()

/**
 * Purchase confirmation sheet for a specific product, modeled as a
 * navigation destination. Mounted on the FloatingWindow back stack via
 * `NavGraphBuilder.bottomSheet<ShopProductSheetRoute>(...)`.
 *
 * The route's presence on the stack **is** the open state — there's no
 * `pendingPurchase` field in `ShopState` anymore. Tap a product in the
 * grid → push this route. Confirm or dismiss → pop. Back gesture works
 * naturally.
 *
 * Deep-link entry from outside the shop (e.g. "Get in shop" on Edit
 * Profile) chains `switchTab(ShopRoute())` then `navigate(...)` with
 * the desired product id. Because the args live on this route — not on
 * `ShopRoute` — `restoreState` on the tab root doesn't clobber them.
 */
@Serializable
data class ShopProductSheetRoute(
    val productId: String,
) : Route()
