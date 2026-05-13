package com.dangerfield.cards

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
}
