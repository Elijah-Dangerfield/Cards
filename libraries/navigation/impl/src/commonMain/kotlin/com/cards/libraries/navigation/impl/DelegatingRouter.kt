package com.dangerfield.cards.libraries.navigation.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.coroutineScope
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetState
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.shouldNotBeCaught
import com.dangerfield.cards.libraries.core.throwIfDebug
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import com.dangerfield.cards.libraries.flowroutines.observeWithLifecycle
import androidx.navigation.NavBackStackEntry
import com.dangerfield.cards.libraries.navigation.AuthGateChecker
import com.dangerfield.cards.libraries.navigation.BlockingErrorRoute
import com.dangerfield.cards.libraries.navigation.NavigationOptions
import com.dangerfield.cards.libraries.navigation.Route
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.RouterBatch
import com.dangerfield.cards.libraries.navigation.TabRoute
import com.dangerfield.cards.libraries.navigation.WebLinkLauncher
import com.dangerfield.cards.libraries.navigation.NavigableWhileBlocked
import kotlin.reflect.KClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = Router::class)
@Inject
class DelegatingRouter(
    private val appScope: AppCoroutineScope,
    private val webLinkLauncher: WebLinkLauncher,
    private val dispatchers: DispatcherProvider,
    private val authGateChecker: AuthGateChecker,
) : Router {

    private val logger = KLog.withTag("DelegatingRouter")
    private val navigationRequests = Channel<NavHostController.() -> Unit>(Channel.UNLIMITED)

    // Drop-oldest with capacity 1 means a tap during a slow scroll won't queue up
    // multiple reselects — the most recent intent wins. extraBufferCapacity=1 (not
    // replay) so a late subscriber doesn't fire on stale taps when navigating back to
    // a tab that was already reselected.
    private val _tabReselects = MutableSharedFlow<TabRoute>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val tabReselects: SharedFlow<TabRoute> = _tabReselects.asSharedFlow()

    override fun notifyTabReselected(route: TabRoute) {
        _tabReselects.tryEmit(route)
    }

    private var navController: NavHostController? = null
    private var processingJob: Job? = null

    private var viewScope: CompletableDeferred<CoroutineScope> = CompletableDeferred()

    fun clearNavController(controller: NavHostController? = null) {
        if (navController === controller || controller == null) {
            logger.d { "Clearing nav controller" }
            processingJob?.cancel()
            processingJob = null
            processingLifecycle = null
            navController = null
            viewScope = CompletableDeferred()
        }
    }

    private var processingLifecycle: Lifecycle? = null

    fun setNavController(
        controller: NavHostController,
        lifecycle: Lifecycle,
        scope: CoroutineScope = lifecycle.coroutineScope,
    ) {
        // Skip redundant binds. On Android, when the Activity handles
        // its own config changes (orientation, etc.), Compose may
        // recompose enough callsites to drive `Bind`'s DisposableEffect
        // through multiple dispose/run cycles with the same controller
        // + same lifecycle. Each rebind cancels the processing job and
        // pushes the start destination, which in turn re-keys every
        // screen's NavBackStackEntry, re-runs each route's
        // LaunchedEffect(Unit), and re-creates per-entry ViewModels —
        // observed as Home re-entering ×N and active-rooms hitting the
        // server ×N during a single rotation.
        if (navController === controller &&
            processingLifecycle === lifecycle &&
            processingJob?.isActive == true
        ) {
            logger.d { "setNavController: redundant rebind ignored" }
            return
        }
        logger.d { "Setting nav controller" }
        viewScope.complete(scope)
        navController = controller
        processingLifecycle = lifecycle
        processingJob?.cancel()
        processingJob = appScope.launch {
            controller.awaitGraphAttachment()
            navigationRequests
                .receiveAsFlow()
                .observeWithLifecycle(lifecycle = lifecycle) { command ->
                    command(controller)
                }
        }
    }

    override fun navigate(route: Route, options: NavigationOptions) {
        // Central auth-gate: a route requiring identity the current user lacks
        // is transparently swapped for the gate sheet (which carries no
        // requirement, so this can't loop). Options are dropped on a gate
        // redirect — clearBackStack etc. shouldn't apply to a dialog on top.
        val effective = authGateChecker.gate(route)
        val effectiveOptions = if (effective === route) options else NavigationOptions()
        enqueueNavigation(
            description = "navigate to ${effective.nameForLogs()}",
            route = effective,
        ) {
            executeNavigate(effective, effectiveOptions)
        }
    }

    override fun goBack() {
        enqueueNavigation("go back") {
            popBackStack()
        }
    }

    override fun popBackTo(route: Route, inclusive: Boolean) {
        enqueueNavigation("popBackTo ${route.nameForLogs()}") {
            popBackStack(route, inclusive)
        }
    }

    override fun switchTab(route: TabRoute) {
        enqueueNavigation(
            description = "switchTab to ${route.nameForLogs()}",
            route = route,
        ) {
            executeSwitchTab(route)
        }
    }

    override fun enterTab(route: TabRoute) {
        enqueueNavigation(
            description = "enterTab to ${route.nameForLogs()}",
            route = route,
        ) {
            navigate(route) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    override fun batch(block: RouterBatch.() -> Unit) {
        enqueueNavigation("batch") {
            // The batch scope dispatches against `this` (the
            // NavHostController already pulled off the queue), so
            // every sub-op runs synchronously inside this single
            // queued execution. Caller scope death between sequential
            // calls inside [block] can't strand the tail because the
            // block has already been captured into the queued lambda.
            val controller = this
            val scope = object : RouterBatch {
                override fun navigate(route: Route, options: NavigationOptions) {
                    val effective = authGateChecker.gate(route)
                    if (controller.shouldBlockNavigation(effective)) {
                        logger.w { "Blocked batch navigate to ${effective.nameForLogs()} (blocking error active)" }
                        return
                    }
                    controller.executeNavigate(
                        effective,
                        if (effective === route) options else NavigationOptions(),
                    )
                }

                override fun switchTab(route: TabRoute) {
                    if (controller.shouldBlockNavigation(route)) {
                        logger.w { "Blocked batch switchTab to ${route.nameForLogs()} (blocking error active)" }
                        return
                    }
                    controller.executeSwitchTab(route)
                }

                override fun popBackTo(route: Route, inclusive: Boolean) {
                    controller.popBackStack(route, inclusive)
                }

                override fun goBack() {
                    controller.popBackStack()
                }
            }
            scope.block()
        }
    }

    override fun <T : Route> backStackEntryFor(routeClass: KClass<T>): NavBackStackEntry? =
        Catching { navController?.getBackStackEntry(routeClass) }
            .logOnFailure("Could not resolve back stack entry for ${routeClass.simpleName}")
            .getOrNull()

    private fun NavHostController.executeNavigate(route: Route, options: NavigationOptions) {
        navigate(route) {
            if (options.clearBackStack) {
                popUpTo(0) { inclusive = true }
            }
            if (options.launchSingleTop) {
                launchSingleTop = true
            }
            if (options.restoreState) {
                restoreState = true
            }
        }
    }

    private fun NavHostController.executeSwitchTab(route: TabRoute) {
        val startDestinationId = graph.findStartDestination().id
        navigate(route) {
            popUpTo(startDestinationId) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    override fun openWebLink(url: String) {
        webLinkLauncher
            .open(url)
            .logOnFailure("Failed to open web link: $url")
            .throwIfDebug()
    }
    @Composable
    fun Bind(navController: NavHostController) {
        val lifecycleOwner = LocalLifecycleOwner.current
        val coroutineScope = rememberCoroutineScope()
        val controllerKey = remember(navController) { navController }

        // Key only on the controller. Earlier this also keyed on
        // `lifecycleOwner`, which made the effect re-run whenever
        // `LocalLifecycleOwner` returned a different instance — happens
        // during config-change-handled rotation on Android even though
        // the Activity (and its real lifecycle) survives. The lifecycle
        // is still captured for the rebind that *does* happen; we just
        // don't treat a transient swap as a trigger. `setNavController`
        // also early-returns on a same-controller + same-lifecycle
        // rebind as a belt-and-suspenders guard.
        //
        // The "binding" log lives inside the effect so it only fires
        // on real rebinds, not every Bind() recomposition — the
        // earlier outside-the-effect log made rotation look like 5+
        // rebinds when it was actually one.
        DisposableEffect(controllerKey) {
            logger.i { "Binding nav controller" }
            setNavController(controllerKey, lifecycleOwner.lifecycle, coroutineScope)
            onDispose { clearNavController(controllerKey) }
        }
    }

    private fun enqueueNavigation(
        description: String,
        route: Route? = null,
        block: NavHostController.() -> Unit,
    ) {
        logger.d { "Enqueuing navigation: $description" }
        navigationRequests.trySend {
            if (route != null && shouldBlockNavigation(route)) {
                logger.w { "Blocked navigation '$description' because a blocking error is active" }
                return@trySend
            }
            Catching { block() }
                .logOnFailure("Navigation failure: $description")
                .throwIfDebug()
        }
    }

    private fun NavHostController.shouldBlockNavigation(route: Route): Boolean {
        if (!isBlockingErrorActive()) return false
        if (route is NavigableWhileBlocked) return false
        KLog.i { "Blocking Navigation for route $route" }
        return true
    }

    private fun NavHostController.isBlockingErrorActive(): Boolean {
        val destination = currentBackStackEntry?.destination ?: return false
        return destination.hasRoute<BlockingErrorRoute>()
    }

    private fun Route.nameForLogs(): String =
        this::class.simpleName ?: this::class.qualifiedName ?: toString()

    private suspend fun NavHostController.awaitGraphAttachment() {
        withContext(dispatchers.mainImmediate) {
            currentBackStackEntryFlow.first()
            delay(100)
        }
    }
}
