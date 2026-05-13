package com.dangerfield.cards.libraries.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * A composable that displays a live camera preview.
 *
 * @param modifier The modifier to apply to this composable.
 * @param captureController Controller to trigger capture from outside the composable.
 * @param useFrontCamera Whether to use the front-facing camera.
 * @param flashEnabled Whether the flash should be enabled.
 * @param onCaptureRequest Callback to trigger photo capture. Call the provided function with a callback to receive the captured image data.
 */
@Composable
expect fun CameraPreview(
    modifier: Modifier = Modifier,
    onCaptureRequest: ((onCaptured: (ByteArray?) -> Unit) -> Unit)? = null,
    captureController: CaptureController? = null,
    useFrontCamera: Boolean = false,
    flashEnabled: Boolean = false,
)

/**
 * Controller to trigger photo capture from outside the CameraPreview composable.
 */
@Stable
class CaptureController {
    internal var onCaptureRequested: ((callback: (ByteArray?) -> Unit) -> Unit)? = null

    /**
     * Capture a photo from the camera preview.
     * @param onCaptured Callback that receives the captured image data, or null if capture failed.
     */
    fun capture(onCaptured: (ByteArray?) -> Unit) {
        onCaptureRequested?.invoke(onCaptured)
    }
}

@Composable
fun rememberCaptureController(): CaptureController {
    return remember { CaptureController() }
}
