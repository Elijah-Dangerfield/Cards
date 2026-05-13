package com.dangerfield.cards.libraries.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberMicrophonePermissionLauncher(
    onResult: (granted: Boolean) -> Unit
): PermissionLauncher {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onResult(granted)
    }
    
    return remember(launcher) {
        object : PermissionLauncher {
            override fun launch() {
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

@Composable
actual fun rememberCameraPermissionLauncher(
    onResult: (granted: Boolean) -> Unit
): PermissionLauncher {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onResult(granted)
    }
    
    return remember(launcher) {
        object : PermissionLauncher {
            override fun launch() {
                launcher.launch(Manifest.permission.CAMERA)
            }
        }
    }
}
