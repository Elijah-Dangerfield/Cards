package com.dangerfield.cards.features.shop

import com.dangerfield.cards.libraries.navigation.Route
import com.dangerfield.cards.libraries.navigation.TabRoute
import kotlinx.serialization.Serializable

/**
 * Shop grid surface — the start destination of [ShopGraph]. Arg-less
 * by design: any "open the purchase sheet for product X" intent
 * belongs on [ShopProductSheetRoute], a sub-route of this tab, **not**
 * on a field here. See `docs/decisions.md` ("Tab roots are arg-less").
 *
 * Not a [TabRoute] — the bottom-bar tab is [ShopGraph]. Switching the
 * Shop tab goes through `switchTab(ShopGraph)`; NavController resolves
 * to this destination via the graph's `startDestination`. Targeting
 * the graph entry (not its leaf child) is what lets `saveState` /
 * `restoreState` actually round-trip the tab's back stack — see the
 * 2026-05-25 decision note.
 */
@Serializable
class ShopRoute : Route()

/**
 * The Shop tab itself: nested graph that wraps [ShopRoute] (grid /
 * tab start) and [ShopProductSheetRoute] (purchase sheet sub-route)
 * so they can share a single `ShopViewModel` scoped to this graph
 * entry — `router.graphScopedViewModel<ShopGraph, ShopViewModel>()`.
 *
 * **Why [TabRoute] (not just a graph id):** the bottom-bar
 * tab-switch recipe (`popUpTo(start, saveState=true) + restoreState`)
 * only round-trips reliably when you navigate to the **graph entry**,
 * not a leaf inside it. With the leaf as the tab target, `restoreState`
 * silently no-ops on return and the VM gets recreated every visit
 * (observed: chip pill flashes "-" → real value, scroll position is
 * lost). Promoting the graph to a `TabRoute` and having `switchTab`
 * target it fixes both.
 */
@Serializable
data object ShopGraph : TabRoute()

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
 * Profile) chains `switchTab(ShopGraph)` then `navigate(...)` with
 * the desired product id. Because the args live on this route — not on
 * `ShopRoute` — `restoreState` on the tab root doesn't clobber them.
 */
@Serializable
data class ShopProductSheetRoute(
    val productId: String,
) : Route()
