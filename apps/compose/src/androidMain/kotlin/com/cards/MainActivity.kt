package com.dangerfield.cards

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.dangerfield.cards.libraries.telemetry.impl.AndroidJankMonitor

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen BEFORE super.onCreate()
        val splashScreen = installSplashScreen()

        super.onCreate(savedInstanceState)

        forwardOAuthCallbackDeepLink(intent)

        // Enable edge-to-edge. The app is dark on every screen, so use dark
        // system bars, which render light (white) status/nav icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        val appComponent = (application as CardsApplication).appComponent
        
        // Keep the splash screen on until AppViewModel has determined the destination.
        // AppViewModel is a singleton, so this is the same instance used in App composable.
        splashScreen.setKeepOnScreenCondition {
            !appComponent.appViewModel.isReady.value
        }

        // JankStats needs a Window, so it can only be armed once the Activity
        // has one. Attaching after setContent would miss the first frames,
        // which are the ones most likely to be janky.
        (appComponent.jankMonitor as? AndroidJankMonitor)?.attach(window)

        setContent {
            App(appComponent)
        }
    }

    override fun onStop() {
        super.onStop()
        // Flush whatever this screen accumulated before the process can be
        // killed in the background. A session that never returns still reports
        // the screen it was on, which is the one worth knowing about.
        appComponent.jankMonitor.onBackground()
    }

    override fun onDestroy() {
        (appComponent.jankMonitor as? AndroidJankMonitor)?.detach()
        super.onDestroy()
    }

    private val appComponent get() = (application as CardsApplication).appComponent

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The browser OAuth flow returns while this Activity is already alive
        // (it's launchMode=standard, but Chrome Custom Tabs deliver back to the
        // running task). Catch the redirect here too, not just in onCreate.
        setIntent(intent)
        forwardOAuthCallbackDeepLink(intent)
    }

    /**
     * Push only the `cards://login-callback` OAuth redirect into the shared
     * [com.dangerfield.cards.libraries.navigation.DeepLinkBridge], where App.kt's
     * collector hands it to supabase-kt for session capture.
     *
     * Other `cards://` links are intentionally left alone: Compose NavHost reads
     * `Activity.intent.data` itself, so re-emitting them here would double-route.
     * The auth callback is the exception — it maps to no nav destination, so
     * NavHost would silently drop it and the session would never land.
     */
    private fun forwardOAuthCallbackDeepLink(intent: Intent?) {
        val url = intent?.data?.toString() ?: return
        val appComponent = (application as CardsApplication).appComponent
        if (appComponent.authRepository.isOAuthRedirect(url)) {
            appComponent.deepLinkBridge.emit(url)
        }
    }
}
