package com.dangerfield.cards.libraries.navigation.impl

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import com.dangerfield.cards.libraries.navigation.AuthGateChecker
import com.dangerfield.cards.libraries.navigation.Route
import com.dangerfield.cards.libraries.navigation.WebLinkLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * **Spike:** can we drive `DelegatingRouter` through a real `NavHostController`
 * from a JVM Compose UI test, and would the bug we shipped today have failed
 * this test?
 *
 * Specifically pins the route-arg-agnostic pop: a back stack with
 * `LobbyRoute(autoCreate=true)` should be popped by `popBackTo(LobbyRoute::
 * class, inclusive=true)` regardless of args — the instance form
 * `popBackTo(LobbyRoute(), inclusive=true)` would silently no-op because
 * Compose Nav matches by serialized route.
 *
 * If this spike passes, the same harness extends to the entry-point composables
 * (PlayMultiplayerFeatureEntryPoint etc.) — they just need their VM factories
 * faked. If it fails, the document in docs/spike-compose-ui-tests.md captures
 * what blocked.
 */
@OptIn(ExperimentalTestApi::class)
class RouterPopBackToSpikeTest {

    /**
     * Currently disabled. The test compiles cleanly against `compose.uiTest`
     * and the `runComposeUiTest` block is the right harness — but on the
     * Android target it requires Robolectric (Compose's idling strategy reads
     * `android.os.Build.FINGERPRINT`, which is null without a real Android
     * runtime). Adopting Robolectric is a real architectural call; see
     * `docs/agent/spike-compose-ui-tests.md` for the spike report and decision.
     *
     * Re-enable by adding `org.robolectric:robolectric:4.x` to androidUnitTest
     * deps and `@RunWith(RobolectricTestRunner::class)` on this class.
     */
    @org.junit.Ignore("Spike — needs Robolectric; see docs/agent/spike-compose-ui-tests.md")
    @Test
    fun popBackToByClass_popsLobbyRouteWithArgs_regardlessOfArgs() = runComposeUiTest {
        val router = newRouter()
        var capturedController: androidx.navigation.NavHostController? = null

        setContent {
            val controller = rememberNavController()
            capturedController = controller
            val lifecycleOwner = LocalLifecycleOwner.current
            LaunchedEffect(controller) {
                router.setNavController(controller, lifecycleOwner.lifecycle)
            }
            NavHost(navController = controller, startDestination = HomeRoute()) {
                composable<HomeRoute> { Text("home") }
                composable<LobbyRoute> { Text("lobby") }
                composable<PlayRoute> { Text("play") }
            }
        }

        // Simulate the production sequence: home → lobby(autoCreate=true) → play.
        // The lobby is pushed with a non-default arg, which is what makes the
        // instance-based pop silently no-op.
        waitForIdle()
        val controller = assertNotNull(capturedController, "nav controller should have been captured during composition")

        router.navigate(LobbyRoute(autoCreate = true))
        waitForIdle()
        router.navigate(PlayRoute(roomCode = "ABCDEF"))
        waitForIdle()

        // Sanity: stack is [Home, LobbyRoute(autoCreate=true), PlayRoute]
        val beforePop = controller.currentBackStack.value.mapNotNull { it.destination.route }
        // Route serialization includes the class qualified name; just assert depth.
        assertEquals(4, beforePop.size, "expected NavGraph + 3 entries; got $beforePop")

        // The actual pop under test. Class-based — should match LobbyRoute
        // regardless of its `autoCreate=true` arg, popping both Lobby and Play.
        router.popBackTo(LobbyRoute::class, inclusive = true)
        waitForIdle()

        val afterPop = controller.currentBackStackEntry?.destination
        assertNotNull(afterPop, "currentBackStackEntry must not be null after pop")
        assertEquals(
            true,
            afterPop.hasRoute<HomeRoute>(),
            "after popBackTo<LobbyRoute>(inclusive=true), the survivor must be Home; was ${afterPop.route}",
        )
    }

    // ---------- minimal scaffolding ----------

    private fun newRouter(): DelegatingRouter = DelegatingRouter(
        appScope = AppCoroutineScope(TestDispatchers),
        webLinkLauncher = NoopWebLinkLauncher,
        dispatchers = TestDispatchers,
        authGateChecker = NoopAuthGateChecker,
    )

    private object NoopWebLinkLauncher : WebLinkLauncher {
        override fun open(url: String): Catching<Unit> = Catching { }
    }

    private object NoopAuthGateChecker : AuthGateChecker {
        override fun gate(route: Route): Route = route
    }

    private object TestDispatchers : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val mainImmediate = Dispatchers.Unconfined
        override val unconfined = Dispatchers.Unconfined
    }
}

// Test routes — open so the file's destinations can be constructed directly
// (Route is `open class` in the prod API; matching it keeps the spike honest).
@Serializable
private open class HomeRoute : Route()

@Serializable
private open class LobbyRoute(val autoCreate: Boolean = false) : Route()

@Serializable
private open class PlayRoute(val roomCode: String) : Route()
