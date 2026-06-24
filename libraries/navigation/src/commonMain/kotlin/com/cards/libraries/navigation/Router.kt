package com.dangerfield.cards.libraries.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import com.dangerfield.cards.libraries.flowroutines.observeWithLifecycle
import com.dangerfield.cards.libraries.ui.snackbar.showSnackBar
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter

data class NavigationOptions(
    val clearBackStack: Boolean = false,
    val launchSingleTop: Boolean = false,
    val restoreState: Boolean = false,
)

interface Router {

    fun navigate(route: Route, options: NavigationOptions = NavigationOptions())

    /**
     * Compile-time guard: a [TabRoute] must go through [switchTab] so the bottom-bar
     * tabs' saved back stacks stay aligned. Pushing one with plain [navigate] mis-roots
     * the new entry under the current tab's stack and breaks subsequent tab swaps.
     */
    @Deprecated(
        message = "TabRoute must go through switchTab() so the tab back stacks stay aligned.",
        replaceWith = ReplaceWith("switchTab(route)"),
        level = DeprecationLevel.ERROR,
    )
    fun navigate(route: TabRoute, options: NavigationOptions = NavigationOptions()): Nothing =
        throw UnsupportedOperationException("Compile-time guard only — use switchTab(route).")

    fun goBack()

    /**
     * Pop the back stack down to the topmost entry whose route is of [routeClass].
     * Matches by Kotlin class rather than serialized-route equality — the caller
     * doesn't need to know which exact argument permutation the destination was
     * pushed with. No-op if no entry of that class is on the stack.
     *
     * There used to be an instance-taking sibling (`popBackTo(route, inclusive)`);
     * it silently no-opped when the route's serialized args didn't match the actual
     * back-stack entry (e.g. popping `LobbyRoute()` while the stack held
     * `LobbyRoute(autoCreate=true)`), which stranded the host on a dead play screen.
     * Deleted in favour of this class-based form which can never miss.
     */
    fun <T : Route> popBackTo(routeClass: KClass<T>, inclusive: Boolean)

    /**
     * Switch to a top-level destination from *inside* the tab system, saving the current
     * tab's stack and restoring any previously-saved stack for the target. Use this for
     * bottom-bar taps OR a feature that needs to deep-link into a different tab (e.g.
     * Edit Profile → Shop). Plain [navigate] of a [TabRoute] won't compile.
     */
    fun switchTab(route: TabRoute)

    /**
     * Hot stream of tab-reselect events — fired when the user taps the already-active
     * bottom-nav tab. Tabs subscribe via [OnTabReselected] to react (e.g. scroll their
     * surface to top, dismiss an open sheet). No event for first-selection or cross-tab
     * switches; that's just [switchTab]. Use [notifyTabReselected] to publish.
     */
    val tabReselects: SharedFlow<TabRoute>

    /** Publish a tab-reselect event. Wired from the bottom-bar onClick when the tap matches the current tab. */
    fun notifyTabReselected(route: TabRoute)

    /**
     * Enter the tab system fresh from *outside* of it — onboarding / sign-in completion.
     * Clears the back stack so the user can't navigate back into the pre-tab flow.
     * Use [switchTab] instead once already inside the tab system.
     */
    fun enterTab(route: TabRoute)

    fun openWebLink(url: String)

    /**
     * Run a sequence of navigation operations atomically against the
     * underlying controller. The whole block runs inside a single
     * queued op, so caller scope death (a ViewModel being torn down,
     * a composable disposing) between sequential calls can't strand
     * the tail of the sequence.
     *
     * Use for deep-link patterns like "switch to Shop tab and push
     * the purchase sheet on top":
     * ```
     * router.batch {
     *     switchTab(ShopGraph)
     *     navigate(ShopProductSheetRoute(productId))
     * }
     * ```
     *
     * The receiver is a narrowed [RouterBatch] — only the navigation
     * primitives are exposed inside, no `batch` recursion, no
     * `openWebLink`.
     */
    fun batch(block: RouterBatch.() -> Unit)

    /**
     * Resolve an existing back-stack entry by route class. Intended
     * for **graph-scoped ViewModel resolution** (see
     * `Router.graphScopedViewModel`), where the caller needs a
     * specific `NavBackStackEntry` to use as a `ViewModelStoreOwner`.
     *
     * Don't use this to navigate, read current state, or short-circuit
     * the navigation API. If you find yourself reaching for the entry
     * for anything else, the operation probably belongs on [Router]
     * directly — promote it.
     *
     * Returns null if the entry isn't on the back stack (e.g., the
     * graph hasn't been entered yet, or you asked for the wrong type).
     */
    fun <T : Route> backStackEntryFor(routeClass: KClass<T>): NavBackStackEntry?
}

inline fun <reified T : Route> Router.backStackEntryFor(): NavBackStackEntry? =
    backStackEntryFor(T::class)

/**
 * Narrowed navigation surface available inside [Router.batch] blocks.
 * Mirrors [Router]'s nav primitives but each call runs immediately
 * against the controller instead of being individually queued.
 */
interface RouterBatch {
    fun navigate(route: Route, options: NavigationOptions = NavigationOptions())

    @Deprecated(
        message = "TabRoute must go through switchTab() inside a batch too.",
        replaceWith = ReplaceWith("switchTab(route)"),
        level = DeprecationLevel.ERROR,
    )
    fun navigate(route: TabRoute, options: NavigationOptions = NavigationOptions()): Nothing =
        throw UnsupportedOperationException("Compile-time guard only — use switchTab(route).")

    fun switchTab(route: TabRoute)
    fun <T : Route> popBackTo(routeClass: KClass<T>, inclusive: Boolean)
    fun goBack()
}

fun <T> Catching<T>.blockingScreenOnError(
    router: Router,
    title: String = "This is super embarrassing",
    subtitle: String = "Our intern Ryan seems to have left a bug in the app. Sorry, you'll need to kill and restart the app.",
    logId: String? = null,
    includeErrorMessage: Boolean = false,
): Catching<T> = this.onFailure {
    val errorCode = it.toKnownErrorCode()
    val resolvedLogId = logId ?: KLog.e(it)?.raw
    router.navigate(
        BlockingErrorRoute(
            title = title,
            subtitle = subtitle,
            errorCode = errorCode,
            logId = resolvedLogId,
            contextMessage = it.message.takeIf { includeErrorMessage }
        )
    )
}

fun <T> Catching<T>.dialogOnError(
    router: Router,
    title: String = "Oops something went wrong",
    subtitle: String = "Please try again",
    actionTitle: String = "Okay",
    action: ErrorDialogAction = ErrorDialogAction.Dismiss,
    logId: String? = null,
    includeErrorMessage: Boolean = false,
    ): Catching<T> = this.onFailure {
    val errorCode = it.toKnownErrorCode()
    val resolvedLogId = logId ?: KLog.e(it)?.raw
    router.navigate(
        ErrorDialogRoute(
            title = title,
            subtitle = subtitle,
            actionTitle = actionTitle,
            action = action,
            errorCode = errorCode,
            logId = resolvedLogId,
            contextMessage = it.message.takeIf { includeErrorMessage }
        )
    )
}

fun <T> Catching<T>.toastOnError(
    title: String = "Oops something went wrong",
    subtitle: String = "Please try again",
): Catching<T> = this.onFailure {
    showSnackBar(
        title = title,
        message = subtitle,
    )
}

/**
 * Subscribe a tab to its own reselect events. Pass any instance of the tab's [TabRoute];
 * matching is by class so it works for plain `class`-based routes (no equals) and
 * `data object`s alike. Collection respects the host lifecycle — pauses below STARTED,
 * resumes on return.
 *
 * ```
 * screen<HomeRoute> {
 *     val scrollState = rememberScrollState()
 *     val scope = rememberCoroutineScope()
 *     router.OnTabReselected(HomeRoute()) { scope.launch { scrollState.animateScrollTo(0) } }
 *     HomeScreen(scrollState = scrollState, ...)
 * }
 * ```
 *
 * Action runs on the main thread (see [observeWithLifecycle]).
 */
@Composable
fun Router.OnTabReselected(route: TabRoute, onReselected: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val routeClass = route::class
    androidx.compose.runtime.LaunchedEffect(routeClass, lifecycle) {
        tabReselects
            .filter { it::class == routeClass }
            .observeWithLifecycle(lifecycle = lifecycle) { onReselected() }
    }
}