package com.dangerfield.cards.libraries.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun ByteArray.toImageBitmap(): ImageBitmap? {
    return try {
        val bitmap = BitmapFactory.decodeByteArray(this, 0, size)
        bitmap?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}
