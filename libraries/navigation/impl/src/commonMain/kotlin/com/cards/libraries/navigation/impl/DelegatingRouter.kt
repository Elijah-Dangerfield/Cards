package com.dangerfield.cards.libraries.navigation.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.coroutineScope
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetState
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.shouldNotBeCaught
import com.dangerfield.cards.libraries.core.throwIfDebug
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.observeWithLifecycle
import com.dangerfield.cards.libraries.navigation.BlockingErrorRoute
import com.dangerfield.cards.libraries.navigation.NavigationOptions
import com.dangerfield.cards.libraries.navigation.Route
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.WebLinkLauncher
import com.dangerfield.cards.libraries.navigation.NavigableWhileBlocked
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
) : Router {

    private val logger = KLog.withTag("DelegatingRouter")
    private val navigationRequests = Channel<NavHostController.() -> Unit>(Channel.UNLIMITED)

    private var navController: NavHostController? = null
    private var processingJob: Job? = null

    private var viewScope: CompletableDeferred<CoroutineScope> = CompletableDeferred()

    fun clearNavController(controller: NavHostController? = null) {
        if (navController === controller || controller == null) {
            logger.d { "Clearing nav controller" }
            processingJob?.cancel()
            processingJob = null
            navController = null
            viewScope = CompletableDeferred()
        }
    }

    fun setNavController(
        controller: NavHostController,
        lifecycle: Lifecycle,
        scope: CoroutineScope = lifecycle.coroutineScope,
    ) {
        logger.d { "Setting nav controller" }
        viewScope.complete(scope)
        navController = controller
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
        enqueueNavigation(
            description = "navigate to ${route.nameForLogs()}",
            route = route,
        ) {
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

    override fun openWebLink(url: String) {
        webLinkLauncher
            .open(url)
            .logOnFailure("Failed to open web link: $url")
            .throwIfDebug()
    }
    @Composable
    fun Bind(navController: NavHostController) {
        logger.i { "Binding nav controller" }
        val lifecycleOwner = LocalLifecycleOwner.current
        val coroutineScope = rememberCoroutineScope()
        val controllerKey = remember(navController) { navController }

        DisposableEffect(controllerKey, lifecycleOwner) {
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
        withContext(Dispatchers.Main.immediate) {
            currentBackStackEntryFlow.first()
            delay(100)
        }
    }
}
