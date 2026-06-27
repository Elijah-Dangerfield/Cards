package com.dangerfield.cards

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen BEFORE super.onCreate()
        val splashScreen = installSplashScreen()

        super.onCreate(savedInstanceState)

        forwardOAuthCallbackDeepLink(intent)

        // Enable edge-to-edge with light status bar (dark icons)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        val appComponent = (application as CardsApplication).appComponent
        
        // Keep the splash screen on until AppViewModel has determined the destination.
        // AppViewModel is a singleton, so this is the same instance used in App composable.
        splashScreen.setKeepOnScreenCondition {
            !appComponent.appViewModel.isReady.value
        }

        setContent {
            App(appComponent)
        }
    }

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
