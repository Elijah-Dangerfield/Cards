package com.dangerfield.cards.libraries.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Resolves [VM] scoped to the [TGraph] nested-graph entry, so screens
 * inside the same `navigation<TGraph>` share a single instance.
 *
 * Pattern (Shop tab — grid + purchase sheet):
 * ```
 * navigation<ShopGraph>(startDestination = ShopRoute()) {
 *     screen<ShopRoute> {
 *         val vm = router.graphScopedViewModel<ShopGraph, ShopViewModel> { shopVmFactory() }
 *         ShopScreen(vm.state, vm::takeAction, ...)
 *     }
 *     bottomSheet<ShopProductSheetRoute> { entry, sheetState ->
 *         val vm = router.graphScopedViewModel<ShopGraph, ShopViewModel> { shopVmFactory() }
 *         // ... same VM instance the screen above is using
 *     }
 * }
 * ```
 *
 * The VM lives as long as **anything** in the [TGraph] subtree is on
 * the back stack, which is the natural scope for tab-wide state. It's
 * torn down when the graph is fully popped (every screen + sheet
 * gone).
 *
 * Why this exists instead of `viewModel(key = "...")` + reaching for
 * a sibling entry via `LocalNavController`: that older pattern
 * (1) leaked the controller to feature code that shouldn't be
 * navigating directly, and (2) coupled the VM lifecycle to one
 * specific child route (the "owner") instead of the graph itself.
 *
 * Returns null if the graph isn't on the back stack — usually a
 * programming error (calling from outside the graph). Caller should
 * `error(...)` or render an empty state.
 */
@Composable
inline fun <reified TGraph : Route, reified VM : ViewModel> Router.graphScopedViewModel(
    noinline factory: () -> VM,
): VM {
    val entry = remember(this) {
        backStackEntryFor<TGraph>()
            ?: error(
                "graphScopedViewModel<${TGraph::class.simpleName}, ${VM::class.simpleName}> " +
                    "called outside the ${TGraph::class.simpleName} subtree — make sure the " +
                    "destination is registered inside navigation<${TGraph::class.simpleName}> { … }.",
            )
    }
    return viewModel(viewModelStoreOwner = entry) { factory() }
}
