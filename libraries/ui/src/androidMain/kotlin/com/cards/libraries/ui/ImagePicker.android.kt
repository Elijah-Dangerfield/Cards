package com.dangerfield.cards.libraries.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.ui.system.LocalDispatcherProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

private const val MAX_EDGE_PX = 1600
private const val JPEG_QUALITY = 70

@Composable
actual fun rememberImagePicker(
    maxImages: Int,
    onResult: (List<PickedImage>) -> Unit,
): ImagePicker {
    val context = LocalContext.current
    val dispatchers = LocalDispatcherProvider.current
    val scope = rememberCoroutineScope()
    // PickMultipleVisualMedia requires a max of at least 2; if the caller asks
    // for a single remaining slot we still floor the contract at 2 and trim the
    // result down to [maxImages] afterward.
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(max(maxImages, 2)),
    ) { uris ->
        if (uris.isEmpty()) {
            onResult(emptyList())
            return@rememberLauncherForActivityResult
        }
        // Decode + downscale off the main thread; the picker hands back content
        // URIs, so reading + recompressing can touch disk.
        scope.launch {
            val images = withContext(dispatchers.default) {
                uris.take(maxImages).mapNotNull { uri -> context.downscaledJpeg(uri) }
            }
            onResult(images)
        }
    }
    return remember(launcher) {
        object : ImagePicker {
            override fun launch() {
                launcher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }
        }
    }
}

private fun Context.downscaledJpeg(uri: Uri): PickedImage? = Catching {
    val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@Catching null
    // Decode the bounds first so a 12MP photo subsamples cheaply instead of
    // allocating the full bitmap just to shrink it.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    val decoded = BitmapFactory.decodeByteArray(
        raw,
        0,
        raw.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) },
    ) ?: return@Catching null
    val scaled = decoded.scaledToMaxEdge()
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
    if (scaled !== decoded) decoded.recycle()
    PickedImage(out.toByteArray())
}.getOrNull()

private fun sampleSizeFor(width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= MAX_EDGE_PX || height / (sample * 2) >= MAX_EDGE_PX) {
        sample *= 2
    }
    return sample
}

private fun Bitmap.scaledToMaxEdge(): Bitmap {
    val longest = max(width, height)
    if (longest <= MAX_EDGE_PX) return this
    val ratio = MAX_EDGE_PX.toFloat() / longest
    return Bitmap.createScaledBitmap(
        this,
        (width * ratio).toInt().coerceAtLeast(1),
        (height * ratio).toInt().coerceAtLeast(1),
        true,
    )
}
