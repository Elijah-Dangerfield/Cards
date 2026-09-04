package com.dangerfield.cards

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.dangerfield.cards.libraries.core.BuildInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.dangerfield.cards.benchmark.BenchmarkHooks
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

        // Before anything reads the start destination. `AppViewModel` resolves it
        // once in its `init`, so a benchmark hook applied later would be a no-op
        // and the generator would silently walk onboarding anyway.
        //
        // `runBlocking` in onCreate is normally indefensible. It is confined to
        // a path that only exists when an explicit benchmark intent arrives AND
        // the build points at dev, so a real launch never reaches it.
        if (BenchmarkHooks.isRequested(intent)) {
            // Only the local flag blocks, and only briefly. Sign-in runs off the
            // launch path: a bots table needs no account, so waiting on the
            // network here would stall the activity for nothing.
            BenchmarkHooks.applyStartDestination(appComponent)
            BenchmarkHooks.signInIfCredentialsProvided(intent, appComponent)
        }

        // Keep the splash screen on until AppViewModel has determined the destination.
        // AppViewModel is a singleton, so this is the same instance used in App composable.
        splashScreen.setKeepOnScreenCondition {
            !appComponent.appViewModel.isReady.value
        }

        // JankStats needs a Window, so it can only be armed once the Activity
        // has one. Attaching after setContent would miss the first frames,
        // which are the ones most likely to be janky.
        (appComponent.jankMonitor as? AndroidJankMonitor)?.attach(window)

        // One toast per launch when this build has produced a violation that no
        // previous run ever did. Not per violation — StrictMode fires constantly
        // and a toast you learn to dismiss reflexively is worse than silence.
        // The shake menu's badge and the log screen carry the detail.
        warnOnNewPerformanceIssues()

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

    private fun warnOnNewPerformanceIssues() {
        if (!BuildInfo.isDebug) return
        lifecycleScope.launch {
            // Violations land during startup, so give the app a beat to produce
            // them rather than reading a count that is still zero.
            delay(NEW_ISSUE_TOAST_DELAY_MS)
            val count = appComponent.strictModeLog.newViolationCount.value
            if (count > 0) {
                Toast.makeText(
                    this@MainActivity,
                    "This build has $count new performance issue(s). Shake to view.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private companion object {
        const val NEW_ISSUE_TOAST_DELAY_MS = 3_000L
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
