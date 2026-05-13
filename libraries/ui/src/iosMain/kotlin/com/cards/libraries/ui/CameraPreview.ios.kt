package com.dangerfield.cards.libraries.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.dangerfield.cards.libraries.ui.nativeviews.LocalNativeViewFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIView

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CameraPreview(
    modifier: Modifier,
    onCaptureRequest: ((onCaptured: (ByteArray?) -> Unit) -> Unit)?,
    captureController: CaptureController?,
    useFrontCamera: Boolean,
    flashEnabled: Boolean,
) {
    val nativeViewFactory = LocalNativeViewFactory.current
    var cameraView by remember { mutableStateOf<UIView?>(null) }

    // Set up capture callback when controller changes
    captureController?.let { controller ->
        controller.onCaptureRequested = { callback ->
            cameraView?.let { view ->
                nativeViewFactory?.capturePhoto(view) { data ->
                    callback(data)
                }
            } ?: callback(null)
        }
    }

    UIKitView(
        factory = {
            val view = nativeViewFactory?.createCameraPreview()
                ?: throw IllegalStateException("NativeViewFactory not available")
            cameraView = view
            view
        },
        modifier = modifier,
        onRelease = { view ->
            nativeViewFactory?.stopCameraPreview(view)
        },
        update = { view ->
            nativeViewFactory?.startCameraPreview(view)
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            cameraView?.let { view ->
                nativeViewFactory?.stopCameraPreview(view)
            }
        }
    }
}
