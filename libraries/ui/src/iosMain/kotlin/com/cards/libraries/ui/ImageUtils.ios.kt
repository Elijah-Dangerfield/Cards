package com.dangerfield.cards.libraries.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.create

@OptIn(ExperimentalForeignApi::class)
actual fun ByteArray.toImageBitmap(): ImageBitmap? {
    return try {
        val nsData = usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
        val skiaImage = Image.makeFromEncoded(toByteArray())
        skiaImage.toComposeImageBitmap()
    } catch (e: Exception) {
        null
    }
}

private fun ByteArray.toByteArray(): ByteArray = this
