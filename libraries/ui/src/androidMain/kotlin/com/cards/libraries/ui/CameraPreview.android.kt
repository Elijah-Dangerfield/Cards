package com.dangerfield.cards.libraries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
actual fun CameraPreview(
    modifier: Modifier,
    onCaptureRequest: ((onCaptured: (ByteArray?) -> Unit) -> Unit)?,
    captureController: CaptureController?,
    useFrontCamera: Boolean,
    flashEnabled: Boolean,
) {
    // TODO: Implement Android camera preview with CameraX
    // For now, show placeholder - Android uses the system camera via ImageCaptureService
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "📷",
                color = Color.White
            )
            Text(
                text = "Camera Ready",
                color = Color.White
            )
        }
    }
}
