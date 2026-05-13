package com.dangerfield.cards.libraries.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermission
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType

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

@Composable
actual fun rememberCameraPermissionLauncher(
    onResult: (granted: Boolean) -> Unit
): PermissionLauncher {
    return remember {
        object : PermissionLauncher {
            override fun launch() {
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                    MainScope().launch {
                        onResult(granted)
                    }
                }
            }
        }
    }
}
