package com.dangerfield.cards.libraries.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.math.max

private const val MAX_EDGE_PX = 1600.0
private const val JPEG_QUALITY = 0.7
private const val IMAGE_TYPE_IDENTIFIER = "public.image"

@Composable
actual fun rememberImagePicker(
    maxImages: Int,
    onResult: (List<PickedImage>) -> Unit,
): ImagePicker = remember(maxImages) { IosImagePicker(maxImages, onResult) }

private class IosImagePicker(
    private val maxImages: Int,
    private val onResult: (List<PickedImage>) -> Unit,
) : ImagePicker {

    // PHPickerViewController references its delegate weakly, so keep a strong
    // ref for the lifetime of the presentation or the callback never fires.
    private var delegate: PhotoPickerDelegate? = null

    override fun launch() {
        val configuration = PHPickerConfiguration().apply {
            selectionLimit = maxImages.toLong()
            filter = PHPickerFilter.imagesFilter()
        }
        val picker = PHPickerViewController(configuration = configuration)
        val pickerDelegate = PhotoPickerDelegate(maxImages) { images ->
            delegate = null
            onResult(images)
        }
        delegate = pickerDelegate
        picker.delegate = pickerDelegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PhotoPickerDelegate(
    private val maxImages: Int,
    private val onResult: (List<PickedImage>) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    override fun picker(
        picker: PHPickerViewController,
        didFinishPicking: List<*>,
    ) {
        picker.dismissViewControllerAnimated(true, null)

        @Suppress("UNCHECKED_CAST")
        val results = (didFinishPicking as List<PHPickerResult>).take(maxImages)
        if (results.isEmpty()) {
            onResult(emptyList())
            return
        }

        // Each item loads asynchronously on an arbitrary queue. Hop back to the
        // main thread before touching the shared list, and only fire [onResult]
        // once the last one resolves (success or failure).
        val collected = mutableListOf<PickedImage>()
        var remaining = results.size
        results.forEach { result ->
            val provider = result.itemProvider
            if (provider.hasItemConformingToTypeIdentifier(IMAGE_TYPE_IDENTIFIER)) {
                provider.loadDataRepresentationForTypeIdentifier(IMAGE_TYPE_IDENTIFIER) { data, _ ->
                    val bytes = data?.let { encodeDownscaledJpeg(it) }
                    MainScope().launch {
                        if (bytes != null) collected.add(PickedImage(bytes))
                        remaining -= 1
                        if (remaining == 0) onResult(collected.toList())
                    }
                }
            } else {
                MainScope().launch {
                    remaining -= 1
                    if (remaining == 0) onResult(collected.toList())
                }
            }
        }
    }
}

// Re-decode the original (possibly HEIC) data through UIImage, then resize +
// JPEG-encode so the attachment is a predictable size and format.
@OptIn(ExperimentalForeignApi::class)
private fun encodeDownscaledJpeg(data: NSData): ByteArray? {
    val resized = UIImage(data = data).downscaledToMaxEdge()
    val jpeg = UIImageJPEGRepresentation(resized, JPEG_QUALITY) ?: return null
    return jpeg.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
private fun UIImage.downscaledToMaxEdge(): UIImage {
    val (width, height) = size.useContents { width to height }
    val longest = max(width, height)
    if (longest <= MAX_EDGE_PX || longest == 0.0) return this
    val scale = MAX_EDGE_PX / longest
    val targetWidth = width * scale
    val targetHeight = height * scale
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(targetWidth, targetHeight), false, 1.0)
    drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight))
    val result = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return result ?: this
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val result = ByteArray(size)
    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return result
}

private fun topViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (controller?.presentedViewController != null) {
        controller = controller.presentedViewController
    }
    return controller
}
