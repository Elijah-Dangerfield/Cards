package com.dangerfield.cards.libraries.ui

import android.content.Context
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AndroidPhotoSaver(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) : PhotoSaver {

    override suspend fun savePhoto(photoData: ByteArray): String? = withContext(dispatchers.io) {
        try {
            val photosDir = File(context.filesDir, "photos")
            if (!photosDir.exists()) {
                photosDir.mkdirs()
            }

            val fileName = "photo_${UUID.randomUUID()}.jpg"
            val file = File(photosDir, fileName)

            FileOutputStream(file).use { outputStream ->
                outputStream.write(photoData)
            }

            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
