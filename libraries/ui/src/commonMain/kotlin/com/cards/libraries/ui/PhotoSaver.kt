package com.dangerfield.cards.libraries.ui

interface PhotoSaver {
    suspend fun savePhoto(photoData: ByteArray): String?
}
