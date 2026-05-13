package com.dangerfield.cards.libraries.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToURL
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosPhotoSaver : PhotoSaver {

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun savePhoto(photoData: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            val fileManager = NSFileManager.defaultManager
            val documentsUrl = fileManager.URLsForDirectory(
                NSDocumentDirectory,
                NSUserDomainMask
            ).firstOrNull() as? NSURL ?: return@withContext null

            val photosDir = documentsUrl.URLByAppendingPathComponent("photos")
            if (photosDir != null && !fileManager.fileExistsAtPath(photosDir.path ?: "")) {
                fileManager.createDirectoryAtURL(photosDir, true, null, null)
            }

            val fileName = "photo_${NSUUID().UUIDString}.jpg"
            val fileUrl = photosDir?.URLByAppendingPathComponent(fileName) ?: return@withContext null

            val nsData = photoData.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = photoData.size.toULong())
            }

            val success = nsData.writeToURL(fileUrl, true)
            if (success) fileUrl.path else null
        } catch (e: Exception) {
            null
        }
    }
}
