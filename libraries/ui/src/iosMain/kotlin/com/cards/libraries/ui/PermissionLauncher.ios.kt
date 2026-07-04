package com.dangerfield.cards.libraries.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession

@Composable
actual fun rememberMicrophonePermissionLauncher(
    onResult: (granted: Boolean) -> Unit
): PermissionLauncher {
    return remember {
        object : PermissionLauncher {
            override fun launch() {
                val audioSession = AVAudioSession.sharedInstance()
                audioSession.requestRecordPermission { granted ->
                    MainScope().launch {
                        onResult(granted)
                    }
                }
            }
        }
    }
}
