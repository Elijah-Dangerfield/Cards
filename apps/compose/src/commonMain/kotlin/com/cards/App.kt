package com.dangerfield.cards

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDeepLinkRequest
import androidx.navigation.NavHostController
import androidx.navigation.NavUri
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.dangerfield.cards.buildinfo.CardsBuildConfig
import com.dangerfield.cards.features.upgrade.AppGuardState
import com.dangerfield.cards.features.upgrade.impl.AppGuardBanner
import com.dangerfield.cards.features.upgrade.impl.AppGuardLayer
import com.dangerfield.cards.libraries.config.AppConfigFlow
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.profile.ProfileRoute
import com.dangerfield.cards.features.shop.ShopProductSheetRoute
import com.dangerfield.cards.features.shop.ShopRoute
import com.dangerfield.cards.libraries.ui.components.AppBottomBar
import com.dangerfield.cards.libraries.ui.components.BottomBarItem
import com.dangerfield.cards.libraries.ui.components.BottomBarSizes
import com.dangerfield.cards.libraries.ui.components.LocalAppBottomBarHeight
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.dialog.DialogHost
import com.dangerfield.cards.libraries.ui.components.dialog.LocalDialogHostState
import com.dangerfield.cards.libraries.ui.components.dialog.rememberDialogHostState
import com.dangerfield.cards.libraries.ui.debug.RecompositionCounter
import com.dangerfield.cards.libraries.ui.snackbar.LocalSnackbarHostState
import com.dangerfield.cards.libraries.ui.snackbar.SnackbarDuration
import com.dangerfield.cards.libraries.ui.snackbar.SnackbarHost
import com.dangerfield.cards.libraries.ui.snackbar.rememberSnackbarHostState
import com.dangerfield.cards.libraries.ui.snackbar.showDebugSnackBar
import com.dangerfield.cards.libraries.ui.system.LocalAppState
import com.dangerfield.cards.libraries.ui.system.LocalBuildInfo
import com.dangerfield.cards.libraries.ui.system.LocalClock
import com.dangerfield.cards.system.AppThemeProvider
import kotlin.reflect.typeOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch

@Composable
fun App(appComponent: AppComponent) {
    val appViewModel = appComponent.appViewModel
    val floatingWindowNavigator = remember { FloatingWindowNavigator() }
    val navController = rememberNavController(floatingWindowNavigator)
    val appRecomposeLogger = remember { KLog.withTag("AppRecompose") }
    val router = remember { appComponent.delegatingRouter }
    val dialogHostState = rememberDialogHostState()
    val snackbarHostState = rememberSnackbarHostState()

    val shakeHandler = remember { appComponent.shakeHandler }
    val deepLinkBridge = remember { appComponent.deepLinkBridge }
    val appConfigFlow = remember { appComponent.appConfigFlow }
    val ensureAppConfigLoaded = remember { appComponent.ensureAppConfigLoaded }
    val configOverrideRepository = remember { appComponent.configOverrideRepository }
    val appScope = rememberCoroutineScope()

    DisposableEffect(shakeHandler) {
        shakeHandler.start()
        onDispose {
            shakeHandler.stop()
        }
    }

    LaunchedEffect(ensureAppConfigLoaded) {
        ensureAppConfigLoaded()
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
        LocalAppState provides appComponent.appState,
        LocalClock provides appComponent.provideClock(),
        LocalBuildInfo provides BuildInfo,
        LocalDialogHostState provides dialogHostState,
        LocalSnackbarHostState provides snackbarHostState,
    ) {
        AppThemeProvider {
            Box(modifier = Modifier.fillMaxSize()) {
                AppGuardGate(
                    appConfigFlow = appConfigFlow,
                    onOpenStore = {
                        router.openWebLink(
                            "https://play.google.com/store/apps/details?id=${CardsBuildConfig.APPLICATION_ID}",
                        )
                    },
                    onClearOverrides = {
                        appScope.launch { configOverrideRepository.clearAll() }
                    },
                ) { guardState ->
                    val startDestination by appViewModel.startDestination.collectAsState()
                    startDestination?.let { route ->
                        AppNavigation(
                            navController = navController,
                            floatingWindowNavigator = floatingWindowNavigator,
                            featureEntryPoints = appComponent.featureEntryPoints,
                            startDestination = route,
                            router = router,
                            topBar = {
                                androidx.compose.foundation.layout.Column {
                                    AppGuardBanner(state = guardState)
                                    OfflineBanner()
                                }
                            },
                            userMessageRepository = appComponent.userMessageRepository,
                            profileRepository = appComponent.profileRepository,
                        )
                    }
                }

                SplashGate()

                UserMessageOverlay(
                    manager = appComponent.inAppMessageManager,
                    router = router,
                )

                DialogHost(
                    modifier = Modifier.matchParentSize(),
                    hostState = dialogHostState
                )

                // Read the back-stack entry *inside* a nested composable so
                // App() itself doesn't recompose on every navigation event.
                // Reading nav state at the App root would re-invoke
                // AppGuardGate's content lambda and rebuild AppNavigation /
                // NavHost — which re-pushes the start destination onto the
                // back stack, churning HomeViewModel and flooding the
                // active-rooms endpoint. Same isolation pattern as
                // [SplashGate].
                AppBottomBarHeightProvider(navController) {
                    SnackbarHost(
                        modifier = Modifier.matchParentSize(),
                        hostState = snackbarHostState,
                    )
                }
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
    userMessageRepository: com.dangerfield.cards.libraries.cards.UserMessageRepository,
    profileRepository: ProfileRepository,
    topBar: @Composable () -> Unit = {},
) {

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navController.currentDestination
    val shouldHideBottomBar = currentBackStackEntry?.tabString() == null
    val unreadNotifications by userMessageRepository.observeUnreadInboxCount()
        .collectAsState(initial = 0)
    val profile by profileRepository.observe().collectAsState(initial = null)
    val authedProfile = profile as? Profile.Authenticated

    Screen(
        topBar = topBar,
        bottomBar = {
            AnimatedVisibility(
                visible = !shouldHideBottomBar,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                // The Shop tab counts as selected for both the grid
                // (`ShopRoute`) and its sheet sub-route
                // (`ShopProductSheetRoute`) — both belong to the Shop tab
                // visually. Treat tap as already-selected if either route
                // is current so we don't re-fire navigation while a sheet
                // is open.
                val isShopSelected = currentDestination?.hasRoute<ShopRoute>() == true ||
                    currentDestination?.hasRoute<ShopProductSheetRoute>() == true

                AppBottomBar(
                    items = listOf(
                        BottomBarItem.Home(isSelected = currentDestination?.hasRoute<HomeRoute>() == true),
                        BottomBarItem.Shop(isSelected = isShopSelected),
                        BottomBarItem.Profile(
                            isSelected = currentDestination?.hasRoute<ProfileRoute>() == true,
                            badgeAmount = unreadNotifications,
                            avatarDisplayName = authedProfile?.displayName,
                            avatarEmoji = authedProfile?.avatarEmoji,
                            avatarBackgroundColor = authedProfile?.avatarBackgroundColor,
                        ),
                    ),
                    onItemClick = { item ->
                        val (isAlreadySelected, route) = when (item) {
                            is BottomBarItem.Home -> (currentDestination?.hasRoute<HomeRoute>() == true) to HomeRoute()
                            is BottomBarItem.Shop -> isShopSelected to ShopRoute()
                            is BottomBarItem.Profile -> (currentDestination?.hasRoute<ProfileRoute>() == true) to ProfileRoute()
                        }

                        if (!isAlreadySelected) {
                            KLog.d { "Navigating to bottom bar route: ${item.title}" }
                            router.switchTab(route)
                        }
                    },
                )
            }
        },
        content = { scaffoldPadding ->
            val statusBarTop = WindowInsets.statusBars
                .asPaddingValues()
                .calculateTopPadding()
            val chromeTopPadding = (scaffoldPadding.calculateTopPadding() - statusBarTop)
                .coerceAtLeast(0.dp)
            NavHost(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = chromeTopPadding),
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
                popEnterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
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
    destination.hasRoute<ShopProductSheetRoute>() -> "Shop"
    destination.hasRoute<ProfileRoute>() -> "Profile"
    else -> null
}

/**
 * Isolates the `appConfigFlow` state read into its own composable so flow emissions
 * (notably the 5s config-refresh timeout on iOS falling back to defaults) cannot
 * recompose `App` and reset the `NavHost`'s start destination — same defensive
 * pattern as [SplashGate].
 */
@Composable
private fun AppGuardGate(
    appConfigFlow: AppConfigFlow,
    onOpenStore: () -> Unit,
    onClearOverrides: () -> Unit,
    content: @Composable (AppGuardState) -> Unit,
) {
    val appConfigMap by appConfigFlow.collectAsState(initial = null)
    val guardState = appConfigMap?.let {
        AppGuardState.from(configMap = it, clientVersionCode = CardsBuildConfig.VERSION_CODE)
    } ?: AppGuardState.Normal

    AppGuardLayer(
        state = guardState,
        onOpenStore = onOpenStore,
        onClearOverrides = onClearOverrides,
    ) {
        content(guardState)
    }
}

/**
 * Reads the current back-stack entry and provides [LocalAppBottomBarHeight]
 * for the subtree (SnackbarHost + any future overlays). Lives in its own
 * composable so the back-stack state read can't trigger an App() recompose
 * — App-level recomposition rebuilds AppNavigation / NavHost which
 * re-pushes the start destination and churns the route's ViewModel
 * (HomeViewModel was being reconstructed on every nav event, firing
 * `GET /v1/me/active-rooms` in a tight loop). Same isolation pattern as
 * [SplashGate].
 */
@Composable
private fun AppBottomBarHeightProvider(
    navController: NavHostController,
    content: @Composable () -> Unit,
) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val shouldHide = currentBackStackEntry?.tabString() == null
    val height = if (shouldHide) 0.dp else BottomBarSizes.BottomBarVerticalHeight
    CompositionLocalProvider(LocalAppBottomBarHeight provides height) {
        content()
    }
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
