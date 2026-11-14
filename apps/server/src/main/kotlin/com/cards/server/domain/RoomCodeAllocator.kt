package com.dangerfield.cards.server.domain

interface RoomCodeAllocator {
    suspend fun <T> withCode(inMemoryCodes: Set<String> = emptySet(), create: suspend (String) -> T): T
}
