package com.dangerfield.cards.libraries.storage

interface CacheJsonSerializer<T : Any> {
    suspend fun read(bytes: ByteArray?): T
    suspend fun write(value: T): ByteArray
}

interface CacheEncryption {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(cipher: ByteArray): ByteArray
}

object NoEncryption : CacheEncryption {
    override fun encrypt(plain: ByteArray) = plain
    override fun decrypt(cipher: ByteArray) = cipher
}