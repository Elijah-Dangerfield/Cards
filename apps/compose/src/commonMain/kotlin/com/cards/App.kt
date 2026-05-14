package com.dangerfield.cards

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDeepLinkRequest
import androidx.navigation.NavHostController
import androidx.navigation.NavUri
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Platform
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.navigation.AnimationType
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Route
import com.dangerfield.cards.libraries.navigation.floatingwindow.FloatingWindowHost
import com.dangerfield.cards.libraries.navigation.floatingwindow.FloatingWindowNavigator
import com.dangerfield.cards.libraries.navigation.impl.DelegatingRouter
import com.dangerfield.cards.libraries.navigation.serializableType
import com.dangerfield.cards.libraries.navigation.toEnterTransition
import com.dangerfield.cards.libraries.navigation.toExitTransition
import com.dangerfield.cards.libraries.navigation.toRouteOrNull
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.profile.ProfileRoute
import com.dangerfield.cards.features.shop.ShopRoute
import com.dangerfield.cards.libraries.ui.PreviewAppState
import com.dangerfield.cards.libraries.ui.components.AppBottomBar
import com.dangerfield.cards.libraries.ui.components.BottomBarItem
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.SnackbarDuration
import com.dangerfield.cards.libraries.ui.components.dialog.DialogHost
import com.dangerfield.cards.libraries.ui.components.dialog.LocalDialogHostState
import com.dangerfield.cards.libraries.ui.components.dialog.rememberDialogHostState
import com.dangerfield.cards.libraries.ui.debug.RecompositionCounter
import com.dangerfield.cards.libraries.ui.snackbar.PresenterSnackbarHost
import com.dangerfield.cards.libraries.ui.snackbar.showDebugSnackBar
import com.dangerfield.cards.libraries.ui.system.LocalAppState
import com.dangerfield.cards.libraries.ui.system.LocalBuildInfo
import com.dangerfield.cards.libraries.ui.system.LocalClock
import com.dangerfield.cards.system.AppThemeProvider
import kotlin.reflect.typeOf
import kotlin.time.Duration.Companion.seconds

@Composable
fun App(appComponent: AppComponent) {
    val appViewModel = appComponent.appViewModel
    val floatingWindowNavigator = remember { FloatingWindowNavigator() }
    val navController = rememberNavController(floatingWindowNavigator)
    val appRecomposeLogger = remember { KLog.withTag("AppRecompose") }
    val router = remember { appComponent.delegatingRouter }
    val dialogHostState = rememberDialogHostState()

    val shakeHandler = remember { appComponent.shakeHandler }
    val deepLinkBridge = remember { appComponent.deepLinkBridge }

    DisposableEffect(shakeHandler) {
        shakeHandler.start()
        onDispose {
            shakeHandler.stop()
        }
    }

    LaunchedEffect(navController, deepLinkBridge) {
        deepLinkBridge.urls.collect { url ->
            Catching {
                val request = NavDeepLinkRequest.Builder.fromUri(NavUri(url)).build()
                navController.handleDeepLink(request)
            }.logOnFailure { "Failed to handle deep link: $url" }
        }
    }

    RecompositionCounter(
        tag = "App",
        logEvery = 1,
        rapidRecompositionThreshold = 6,
        rapidRecompositionWindow = 60.seconds,
        onRecompose = { count ->
            val message = if (count == 1L) {
                "App recomposed (this should be rare)"
            } else {
                "App recomposed $count times"
            }
            appRecomposeLogger.w { message }
        },
        onRapidRecomposition = { info ->
            appRecomposeLogger.e {
                "Rapid recompositions: ${info.countInWindow} in ${info.windowMillis}ms (total=${info.totalCount})"
            }
            showDebugSnackBar(
                title = "Performance hiccup",
                message = "App recomposed ${info.countInWindow}× in ${info.windowMillis}ms.",
                duration = SnackbarDuration.Long,
                withDismissAction = true,
            )
        }
    )

    CompositionLocalProvider(
        LocalAppState provides PreviewAppState,
        LocalClock provides appComponent.provideClock(),
        LocalBuildInfo provides BuildInfo,
        LocalDialogHostState provides dialogHostState
    ) {
        AppThemeProvider {
            Box(modifier = Modifier.fillMaxSize()) {
                AppNavigation(
                    navController = navController,
                    floatingWindowNavigator = floatingWindowNavigator,
                    featureEntryPoints = appComponent.featureEntryPoints,
                    startDestination = appViewModel.startDestination,
                    router = router,
                )

                SplashGate()

                DialogHost(
                    modifier = Modifier.matchParentSize(),
                    hostState = dialogHostState
                )
            }
        }
    }
}

@Composable
private fun AppNavigation(
    navController: NavHostController,
    floatingWindowNavigator: FloatingWindowNavigator,
    featureEntryPoints: Set<FeatureEntryPoint>,
    startDestination: Route,
    router: DelegatingRouter,
) {

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navController.currentDestination
    val shouldHideBottomBar = currentBackStackEntry?.tabString() == null

    Screen(
        snackbarHost = {
            PresenterSnackbarHost()
        },
        bottomBar = {
            AnimatedVisibility(
                visible = !shouldHideBottomBar,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                AppBottomBar(
                    items = listOf(
                        BottomBarItem.Home(isSelected = currentDestination?.hasRoute<HomeRoute>() == true),
                        BottomBarItem.Shop(isSelected = currentDestination?.hasRoute<ShopRoute>() == true),
                        BottomBarItem.Profile(isSelected = currentDestination?.hasRoute<ProfileRoute>() == true),
                    ),
                    onItemClick = { item ->
                        val (isAlreadySelected, route) = when (item) {
                            is BottomBarItem.Home -> (currentDestination?.hasRoute<HomeRoute>() == true) to HomeRoute()
                            is BottomBarItem.Shop -> (currentDestination?.hasRoute<ShopRoute>() == true) to ShopRoute()
                            is BottomBarItem.Profile -> (currentDestination?.hasRoute<ProfileRoute>() == true) to ProfileRoute()
                        }

                        if (!isAlreadySelected) {
                            navController.navigate(route) {
                                // Pop back to the start destination so the back stack stays shallow
                                popUpTo(HomeRoute::class) {
                                    saveState = true
                                }
                                // Avoid multiple copies of the same destination
                                launchSingleTop = true
                                // Restore inner-tab state when reselecting a previously selected tab
                                restoreState = true
                            }
                        }
                    },
                )
            }
        },
        content = {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                //To make this more readable consider Screens A and B
                enterTransition = {
                    // A -> B
                    // How should we animate the B screen?
                    // Enter animation should match B's Enter
                    val targetRoute = targetState.toRouteOrNull<Route>()
                    val (animationType, reason) = when {
                        isSwitchingTabs() -> AnimationType.None to "Switching tabs"
                        targetRoute != null -> targetRoute.enter to "Using target route enter animation"
                        else -> AnimationType.None to "Target destination is not a Route; default to none"
                    }

                    animationType.toEnterTransition()
                },
                popEnterTransition = {
                    // Popping from B back to A
                    // How should we animate the A screen?
                    // Enter animation should match initials pop EXIT transition
                    // AKA if B slides out, A should slide IN
                    val initialRoute = initialState.toRouteOrNull<Route>()
                    val targetRoute = targetState.toRouteOrNull<Route>()
                    val (animationType, reason) = when {
                        isSwitchingTabs() -> AnimationType.None to "Switching tabs"
                        initialRoute != null -> initialRoute.popExit.opposite() to "Mirroring initial popExit animation"
                        targetRoute != null -> targetRoute.enter to "Fallback to target route enter animation"
                        else -> AnimationType.None to "No route metadata; default to none"
                    }

                    animationType.toEnterTransition()
                },
                exitTransition = {
                    // A -> B
                    // Initial: A | Target B
                    // How should we animate the A screen
                    // Exit animation should match A's Exit
                    val initialRoute = initialState.toRouteOrNull<Route>()
                    val (animationType, reason) = when {
                        isSwitchingTabs() -> AnimationType.None to "Switching tabs"
                        initialRoute != null -> initialRoute.exit to "Using initial route exit animation"
                        else -> AnimationType.None to "Initial destination is not a Route; default to none"
                    }

                    animationType.toExitTransition()
                },
                popExitTransition = {
                    // Popping from B back to A
                    // Initial: B | Target A
                    // How should we animate the B screen
                    // Exit animation should match B's pope Exit
                    val initialRoute = initialState.toRouteOrNull<Route>()

                    val (animationType, reason) = when {
                        isSwitchingTabs() -> AnimationType.None to "Switching tabs"
                        initialRoute != null -> initialRoute.popExit to "Using initial route popExit animation"
                        else -> AnimationType.None to "Initial destination is not a Route; default to none"
                    }

                    animationType.toExitTransition()
                },
                typeMap = mapOf(
                    typeOf<AnimationType>() to serializableType<AnimationType>()
                )
            ) {
                featureEntryPoints.forEach { entryPoint ->
                    with(entryPoint) {
                        buildNavGraph(router)
                    }
                }
            }

            FloatingWindowHost(floatingWindowNavigator)

            router.Bind(navController)
        },
    )
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isSwitchingTabs(): Boolean {
    val initialTab = initialState.tabString()
    val targetTab = targetState.tabString()
    val isBothTabs = initialTab != null && targetTab != null
    return isBothTabs && initialTab != targetTab
}

private fun NavBackStackEntry.tabString(): String? = when {
    destination.hasRoute<HomeRoute>() -> "Home"
    destination.hasRoute<ShopRoute>() -> "Shop"
    destination.hasRoute<ProfileRoute>() -> "Profile"
    else -> null
}

/**
 * Isolates the splash-overlay's `hasShownSplash` state read into its own composable
 * so that flipping it cannot recompose `App` and, in particular, cannot cause
 * `AppNavigation` / `NavHost` to rebuild its graph — which was previously pushing
 * a second copy of the start destination onto the back stack.
 *
 * Only renders on iOS. Android uses the native splash API instead.
 */
@Composable
private fun SplashGate() {
    if (BuildInfo.platform != Platform.iOS) return
    var hasShownSplash by rememberSaveable { mutableStateOf(false) }
    if (!hasShownSplash) {
        SplashOverlay(onComplete = { hasShownSplash = true })
    }
}
