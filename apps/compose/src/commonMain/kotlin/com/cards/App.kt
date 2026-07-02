package com.dangerfield.cards

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.navigation.NavGraphBuilder
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
import com.dangerfield.cards.libraries.identity.profile.ProfileEditRejection
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.avatarBackgroundColorOrNull
import com.dangerfield.cards.libraries.identity.profile.avatarEmojiOrNull
import com.dangerfield.cards.libraries.identity.profile.displayNameOrNull
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Platform
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.cards.Telemetry
import com.dangerfield.cards.libraries.navigation.AnimationType
import com.dangerfield.cards.libraries.navigation.baseRouteTypeMap
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Route
import com.dangerfield.cards.libraries.navigation.floatingwindow.FloatingWindowHost
import com.dangerfield.cards.libraries.navigation.floatingwindow.FloatingWindowNavigator
import com.dangerfield.cards.libraries.navigation.impl.DelegatingRouter
import com.dangerfield.cards.libraries.navigation.AccessDeniedRoute
import com.dangerfield.cards.libraries.core.AuthReason
import com.dangerfield.cards.libraries.navigation.AuthGateRoute
import com.dangerfield.cards.libraries.navigation.SessionExpiredRoute
import com.dangerfield.cards.libraries.navigation.dialog
import com.dangerfield.cards.libraries.navigation.serializableType
import com.dangerfield.cards.libraries.navigation.toEnterTransition
import com.dangerfield.cards.libraries.navigation.toExitTransition
import com.dangerfield.cards.libraries.navigation.toRouteOrNull
import com.dangerfield.cards.features.onboarding.OnboardingRoute
import com.dangerfield.cards.features.profile.ClaimAccountRoute
import com.dangerfield.cards.features.profile.FeedbackRoute
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.profile.ProfileRoute
import com.dangerfield.cards.features.shop.ShopGraph
import com.dangerfield.cards.features.shop.ShopProductSheetRoute
import com.dangerfield.cards.features.shop.ShopRoute
import com.dangerfield.cards.libraries.cards.XP_BOOST_DEFAULT_DURATION_MS
import com.dangerfield.cards.libraries.ui.components.AppBottomBar
import com.dangerfield.cards.libraries.ui.components.BottomBarItem
import com.dangerfield.cards.libraries.ui.components.rememberBoostRemainingMs
import com.dangerfield.cards.libraries.ui.components.BottomBarSizes
import com.dangerfield.cards.libraries.ui.components.LocalAppBottomBarHeight
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.dialog.DialogHost
import com.dangerfield.cards.libraries.ui.components.dialog.LocalDialogHostState
import com.dangerfield.cards.libraries.ui.components.dialog.rememberDialogHostState
import com.dangerfield.cards.libraries.ui.debug.RecompositionCounter
import com.dangerfield.cards.libraries.ui.snackbar.LocalSnackbarHostState
import com.dangerfield.cards.libraries.navigation.NavigationOptions
import com.dangerfield.cards.libraries.ui.snackbar.SnackbarDuration
import com.dangerfield.cards.libraries.ui.snackbar.SnackbarLevel
import com.dangerfield.cards.libraries.ui.snackbar.showSnackBar
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.profile_edit_sync_rejected_avatar
import cards.libraries.resources.generated.resources.profile_edit_sync_rejected_name_invalid
import cards.libraries.resources.generated.resources.profile_edit_sync_rejected_name_taken
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import com.dangerfield.cards.libraries.ui.snackbar.SnackbarHost
import com.dangerfield.cards.libraries.ui.snackbar.rememberSnackbarHostState
import com.dangerfield.cards.libraries.ui.snackbar.showDebugSnackBar
import com.dangerfield.cards.libraries.ui.system.AccountSetupRetryStatus
import com.dangerfield.cards.libraries.ui.system.LocalAccountSetupRetry
import com.dangerfield.cards.libraries.ui.system.LocalAppState
import com.dangerfield.cards.libraries.ui.system.LocalBuildInfo
import com.dangerfield.cards.libraries.ui.system.LocalClock
import com.dangerfield.cards.libraries.ui.system.LocalLevelCurve
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
    val progressionConfig = remember { appComponent.progressionConfig }
    val appScope = rememberCoroutineScope()

    // The level curve rides app-config, so recompute it whenever config rolls
    // in (it falls back to the bundled default until then). Provided once at the
    // root via [LocalLevelCurve] so every display site derives the shown level
    // against the same curve the grant path uses.
    val appConfigForCurve by appConfigFlow.collectAsState(initial = null)
    val levelCurve = remember(appConfigForCurve) { progressionConfig.levelCurve() }

    // Boot-warm every @AutoInit singleton. Resolving the Set forces
    // each contributor to construct, running their `init {}` blocks —
    // products catalog hydrates from disk, AppEventDispatcher attaches
    // its lifecycle observer, ProfileRepository fires its avatar-pack
    // prefetch. Wrapped in `remember` so this resolves exactly once
    // per Activity lifetime, even though App() recomposes. See
    // [AutoInit] for the contract.
    remember { appComponent.autoInits }

    DisposableEffect(shakeHandler) {
        shakeHandler.start()
        onDispose {
            shakeHandler.stop()
        }
    }

    LaunchedEffect(ensureAppConfigLoaded) {
        ensureAppConfigLoaded()
    }

    // Surface a "couldn't save" snackbar when a profile edit queued offline is
    // refused by the server on flush (name taken while away, etc.) — the
    // optimistic value already reverted, so without this the change would
    // silently disappear. App-root so it shows wherever the user is by then.
    LaunchedEffect(Unit) {
        appComponent.profileRepository.observeEditRejections().collect { rejection ->
            val message = getString(
                when (rejection) {
                    ProfileEditRejection.DisplayNameTaken -> Res.string.profile_edit_sync_rejected_name_taken
                    ProfileEditRejection.InvalidDisplayName -> Res.string.profile_edit_sync_rejected_name_invalid
                    ProfileEditRejection.InvalidAvatarEmoji,
                    ProfileEditRejection.InvalidAvatarBackgroundColor -> Res.string.profile_edit_sync_rejected_avatar
                },
            )
            showSnackBar(message = message, level = SnackbarLevel.Error)
        }
    }

    val authRepository = remember { appComponent.authRepository }
    LaunchedEffect(navController, deepLinkBridge, authRepository) {
        deepLinkBridge.urls.collect { url ->
            // The browser OAuth return trip (`cards://login-callback#...`) carries
            // a Supabase session in its fragment, not a navigable destination —
            // hand it to supabase-kt to import instead of the nav graph. Every
            // other `cards://` link routes as before. The repo emits the new
            // AuthState on success, which the app's auth gate reacts to.
            if (authRepository.isOAuthRedirect(url)) {
                Catching { authRepository.completeOAuthRedirect(url) }
                    .logOnFailure { "Failed to complete OAuth redirect" }
            } else {
                Catching {
                    val request = NavDeepLinkRequest.Builder.fromUri(NavUri(url)).build()
                    navController.handleDeepLink(request)
                }.logOnFailure { "Failed to handle deep link: $url" }
            }
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

    val accountSetupStatus = rememberAccountSetupStatus(appComponent.guestAccountCreator)
    val accountSetupRetry = AccountSetupRetryStatus(
        pending = accountSetupStatus.pending,
        isRetrying = accountSetupStatus.isRetrying,
        onRetry = { appComponent.guestAccountCreator.retry() },
    )

    CompositionLocalProvider(
        LocalAppState provides appComponent.appState,
        LocalClock provides appComponent.provideClock(),
        LocalBuildInfo provides BuildInfo,
        LocalDialogHostState provides dialogHostState,
        LocalSnackbarHostState provides snackbarHostState,
        LocalLevelCurve provides levelCurve,
        LocalAccountSetupRetry provides accountSetupRetry,
    ) {
        AppThemeProvider {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Debug-only: swipe in from the right edge to jump straight
                    // to the feedback form. No-op in release.
                    .debugRightEdgeSwipe(enabled = BuildInfo.isDebug) {
                        router.navigate(FeedbackRoute())
                    },
            ) {
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
                    val bootComplete by appViewModel.isBootComplete.collectAsState()
                    val startDestination by appViewModel.startDestination.collectAsState()
                    val route = startDestination
                    if (bootComplete && route != null) {
                        AppNavigation(
                            navController = navController,
                            floatingWindowNavigator = floatingWindowNavigator,
                            featureEntryPoints = appComponent.featureEntryPoints,
                            startDestination = route,
                            router = router,
                            topBar = {
                                androidx.compose.foundation.layout.Column {
                                    AppGuardBanner(state = guardState)
                                    AppStatusBanners(appComponent.guestAccountCreator)
                                }
                            },
                            userMessageRepository = appComponent.userMessageRepository,
                            profileRepository = appComponent.profileRepository,
                            shopBadgeStateRepository = appComponent.shopBadgeStateRepository,
                            xpBoostRepository = appComponent.xpBoostRepository,
                            telemetry = appComponent.telemetry,
                        )
                    } else {
                        BootLoadingScreen()
                    }
                }

                SplashGate()

                AccountSetupExplainerDialog(
                    creator = appComponent.guestAccountCreator,
                    appCache = appComponent.appCache,
                )

                // The auth server rejected our session mid-run: push a blocking
                // SessionExpired screen (kept on top, stack intact) that owns
                // Retry (recover in place) + Logout (tear down to onboarding).
                // An ambient network event can't present this from a feature
                // screen, so it routes here. The screen picks guest vs claimed
                // copy off wasAnonymous itself.
                LaunchedEffect(Unit) {
                    appViewModel.sessionExpired.collect { event ->
                        router.navigate(
                            SessionExpiredRoute(wasAnonymous = event.wasAnonymous),
                            NavigationOptions(launchSingleTop = true),
                        )
                    }
                }

                // Server returned the locked `403` access-denied envelope: push
                // the blocking AccessDenied screen. Same launchSingleTop pattern
                // as SessionExpired — a burst of denied calls collapses to one
                // screen on top. The screen localizes title/body off `reason`
                // and surfaces the appeal link.
                LaunchedEffect(Unit) {
                    appViewModel.accessDenied.collect { denial ->
                        router.navigate(
                            AccessDeniedRoute(
                                reason = denial.reason,
                                appealUrl = denial.appealUrl,
                            ),
                            NavigationOptions(launchSingleTop = true),
                        )
                    }
                }

                UserMessageOverlay(
                    manager = appComponent.inAppMessageManager,
                    router = router,
                )

                DialogHost(hostState = dialogHostState)

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

/**
 * The simple class name of a destination's route, for telemetry tagging.
 *
 * Type-safe nav stores the route as its serializer name — the fully-qualified
 * class name followed by argument placeholders, e.g.
 * `com.dangerfield.cards.features.shop.ShopProductSheetRoute/{enter}/{exit}`
 * or `...HomeRoute?tab={tab}`. We strip the args and the package to get just
 * `ShopProductSheetRoute`, keeping the tag low-cardinality and readable.
 * Returns null for unnamed/graph destinations.
 */
private fun NavDestination.routeClassNameOrNull(): String? =
    route
        ?.substringBefore('/')
        ?.substringBefore('?')
        ?.substringAfterLast('.')
        ?.takeIf { it.isNotBlank() }

@Composable
private fun AppNavigation(
    navController: NavHostController,
    floatingWindowNavigator: FloatingWindowNavigator,
    featureEntryPoints: Set<FeatureEntryPoint>,
    startDestination: Route,
    router: DelegatingRouter,
    userMessageRepository: com.dangerfield.cards.libraries.cards.UserMessageRepository,
    profileRepository: ProfileRepository,
    shopBadgeStateRepository: com.dangerfield.cards.libraries.products.ShopBadgeStateRepository,
    xpBoostRepository: com.dangerfield.cards.libraries.cards.XpBoostRepository,
    telemetry: Telemetry,
    topBar: @Composable () -> Unit = {},
) {

    // Tag every crash/error with the route the user is currently on. Sheets
    // and dialogs are real destinations on this same back stack (see
    // `bottomSheet`/`dialog` nav builders), so an open sheet wins over the
    // screen beneath it — exactly the granularity we want for triage. Pushed
    // from a LaunchedEffect keyed on the name so we only touch the Sentry
    // scope when the route actually changes, not on every recomposition.
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRouteName = currentBackStackEntry?.destination?.routeClassNameOrNull()
    LaunchedEffect(currentRouteName) {
        currentRouteName?.let { telemetry.setCurrentRoute(it) }
    }

    Screen(
        topBar = topBar,
        bottomBar = {
            // The bottom bar's reactive reads (profile avatar, unread badge, shop
            // dot, xp-boost tint) live here, NOT in AppNavigation's body — so when
            // any of them emit, only the bar recomposes. Reading them above would
            // recompose AppNavigation, which churns [AppNavHost] and (because the
            // NavHost graph used to rebuild on recomposition) re-pushed the start
            // destination, silently recreating the current screen's ViewModel.
            AppChromeBottomBar(
                navController = navController,
                router = router,
                userMessageRepository = userMessageRepository,
                profileRepository = profileRepository,
                shopBadgeStateRepository = shopBadgeStateRepository,
                xpBoostRepository = xpBoostRepository,
            )
        },
        content = { scaffoldPadding ->
            AppNavHost(
                navController = navController,
                startDestination = startDestination,
                featureEntryPoints = featureEntryPoints,
                router = router,
                floatingWindowNavigator = floatingWindowNavigator,
                contentPadding = scaffoldPadding,
            )
        },
    )
}

/**
 * Owns the [NavHost]. Kept as its own composable taking only stable, graph-shaping
 * inputs so the volatile chrome reads in [AppChromeBottomBar] can't recompose it.
 *
 * The graph builder is **remembered**: navigation-compose keys the graph's internal
 * `remember` on the builder lambda, so a fresh lambda each recomposition would rebuild
 * the graph and re-push the start destination — which destroys and recreates the
 * current destination's [NavBackStackEntry], silently resetting its ViewModel (the
 * onboarding "back to Welcome" bounce). Remembering it keeps the graph built once.
 */
@Composable
private fun AppNavHost(
    navController: NavHostController,
    startDestination: Route,
    featureEntryPoints: Set<FeatureEntryPoint>,
    router: DelegatingRouter,
    floatingWindowNavigator: FloatingWindowNavigator,
    contentPadding: PaddingValues,
) {
    val graph: NavGraphBuilder.() -> Unit = remember(featureEntryPoints, router) {
        {
            featureEntryPoints.forEach { entryPoint ->
                with(entryPoint) {
                    buildNavGraph(router)
                }
            }
            // Shared auth-gate sheet the router substitutes for a route the
            // current user can't enter (see AuthGateChecker). Registered here
            // (not in a feature) because its CTAs span onboarding + claim, which
            // the app layer already knows about.
            dialog<AuthGateRoute>(
                // AuthGateRoute carries an AuthReason arg; iOS/Native needs an
                // explicit NavType for it (the base-route types come from the
                // builder's baseRouteTypeMap).
                typeMap = mapOf(typeOf<AuthReason>() to serializableType<AuthReason>()),
            ) { entry, dialogState ->
                val reason = entry.toRouteOrNull<AuthGateRoute>()?.reason
                    ?: AuthReason.NeedAccount
                AuthGateSheet(
                    reason = reason,
                    state = dialogState,
                    onCreateAccount = { router.goBack(); router.navigate(OnboardingRoute()) },
                    onSaveAccount = { router.goBack(); router.navigate(ClaimAccountRoute()) },
                    onDismiss = { router.goBack() },
                )
            }
        }
    }

    val statusBarTop = WindowInsets.statusBars
        .asPaddingValues()
        .calculateTopPadding()
    val chromeTopPadding = (contentPadding.calculateTopPadding() - statusBarTop)
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
        typeMap = baseRouteTypeMap,
        builder = graph,
    )

    FloatingWindowHost(floatingWindowNavigator)

    router.Bind(navController)
}

/**
 * The bottom-bar chrome. All of its reactive reads (route selection, unread badge,
 * shop dot, profile avatar, xp-boost tint) are confined here so their emissions
 * recompose only the bar — never [AppNavigation] or the [AppNavHost] it wraps.
 */
@Composable
private fun AppChromeBottomBar(
    navController: NavHostController,
    router: DelegatingRouter,
    userMessageRepository: com.dangerfield.cards.libraries.cards.UserMessageRepository,
    profileRepository: ProfileRepository,
    shopBadgeStateRepository: com.dangerfield.cards.libraries.products.ShopBadgeStateRepository,
    xpBoostRepository: com.dangerfield.cards.libraries.cards.XpBoostRepository,
) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navController.currentDestination
    val shouldHideBottomBar = currentBackStackEntry?.tabString() == null
    val unreadNotifications by userMessageRepository.observeUnreadInboxCount()
        .collectAsState(initial = 0)
    val profile by profileRepository.observe().collectAsState(initial = null)
    val shopHasUnseenItems by shopBadgeStateRepository.observeHasUnseenItems()
        .collectAsState(initial = false)
    val shopMarkSeenScope = rememberCoroutineScope()

    // While an XP boost is burning, the bottom bar tints + grows a thin draining
    // progress line above it so the active window is visible from any tab. Null
    // fraction (no live window) leaves the bar in its default treatment.
    val xpBoostStatus by xpBoostRepository.observe()
        .collectAsState(initial = com.dangerfield.cards.libraries.cards.XpBoostStatus.None)
    val boostRemainingMs = rememberBoostRemainingMs(xpBoostStatus.expiresAtEpochMs)
    val boostProgress = if (boostRemainingMs > 0L) {
        (boostRemainingMs.toFloat() / XP_BOOST_DEFAULT_DURATION_MS.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }

    AnimatedVisibility(
        visible = !shouldHideBottomBar,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        // The Shop tab counts as selected for both the grid (`ShopRoute`) and its
        // sheet sub-route (`ShopProductSheetRoute`) — both belong to the Shop tab
        // visually. Treat tap as already-selected if either route is current so we
        // don't re-fire navigation while a sheet is open.
        val isShopSelected = currentDestination?.hasRoute<ShopRoute>() == true ||
            currentDestination?.hasRoute<ShopProductSheetRoute>() == true

        AppBottomBar(
            boostProgress = boostProgress,
            items = listOf(
                BottomBarItem.Home(isSelected = currentDestination?.hasRoute<HomeRoute>() == true),
                BottomBarItem.Shop(
                    isSelected = isShopSelected,
                    // Dot only shows when the user is NOT already on the Shop tab —
                    // once you're looking at the catalog, the "you should look at
                    // the catalog" cue is noise.
                    showsBadgeDot = shopHasUnseenItems && !isShopSelected,
                ),
                BottomBarItem.Profile(
                    isSelected = currentDestination?.hasRoute<ProfileRoute>() == true,
                    badgeAmount = unreadNotifications,
                    // Honor a locally-chosen (offline) identity so the tab avatar
                    // isn't a generic icon for a Fallback user.
                    avatarDisplayName = profile?.displayNameOrNull,
                    avatarEmoji = profile?.avatarEmojiOrNull,
                    avatarBackgroundColor = profile?.avatarBackgroundColorOrNull,
                ),
            ),
            onItemClick = { item ->
                val (isAlreadySelected, route) = when (item) {
                    is BottomBarItem.Home -> (currentDestination?.hasRoute<HomeRoute>() == true) to HomeRoute()
                    // Tab target is the graph entry, not the leaf — only navigating to
                    // ShopGraph keeps saveState/restoreState working across tab switches.
                    // (Targeting the inner ShopRoute silently no-ops restoreState and
                    // recreates the ShopViewModel on every visit.)
                    is BottomBarItem.Shop -> isShopSelected to ShopGraph
                    is BottomBarItem.Profile -> (currentDestination?.hasRoute<ProfileRoute>() == true) to ProfileRoute()
                }

                if (item is BottomBarItem.Shop) {
                    // Tab open clears the "new items" dot — whether this is a
                    // first-tap or a re-tap, the user is now looking at the catalog.
                    // Fire-and-forget via the composition scope; the write is
                    // idempotent against the current catalog so a double-tap is harmless.
                    shopMarkSeenScope.launch {
                        shopBadgeStateRepository.markCurrentItemsSeen()
                    }
                }

                if (isAlreadySelected) {
                    // Re-tap on the active tab — let the tab react (scroll to top,
                    // dismiss sheet, etc.) instead of swallowing the tap. Subscribers
                    // wire up via `router.OnTabReselected(...)`.
                    router.notifyTabReselected(route)
                } else {
                    KLog.d { "Navigating to bottom bar route: ${item.title}" }
                    router.switchTab(route)
                }
            },
        )
    }
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
