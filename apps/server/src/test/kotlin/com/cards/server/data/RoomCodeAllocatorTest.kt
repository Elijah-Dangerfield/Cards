package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.domain.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RoomCodeAllocatorTest {
    @Test fun skipsSavedRoomsStandingTablesAndLoadedRooms() = runTest {
        val random = Random(43)
        fun code() = buildString { repeat(6) { append(InMemoryRoomService.CODE_ALPHABET[random.nextInt(InMemoryRoomService.CODE_ALPHABET.length)]) } }
        val saved = code(); val standing = code(); val loaded = code(); val free = code()
        val store = object : RoomStore by NoOpRoomStore { override suspend fun codeTaken(code: String) = code == saved }
        val tables = object : StandingTableRepository by NoOpStandingTableRepository { override suspend fun exists(code: String) = code == standing }
        val allocator = DefaultRoomCodeAllocator(Random(43), store, tables)
        assertEquals(free, allocator.withCode(setOf(loaded)) { it })
    }
    @Test fun allocationRemainsLockedUntilInsertCompletes() = runTest {
        val used = mutableSetOf<String>()
        val store = object : RoomStore by NoOpRoomStore { override suspend fun codeTaken(code: String) = code in used }
        val random = object : Random() {
            var calls = 0
            override fun nextBits(bitCount: Int): Int = error("Only bounded nextInt expected")
            override fun nextInt(until: Int): Int = if (calls++ < 12) 0 else 1
        }
        val allocator = DefaultRoomCodeAllocator(random, store, NoOpStandingTableRepository)
        val codes = List(2) { async { allocator.withCode { code -> yield(); used.add(code); code } } }.awaitAll()
        assertEquals(listOf("AAAAAA", "BBBBBB"), codes)
    }
    @Test fun remembersOnlyARecentWindowOfAllocatedCodes() = runTest {
        val allocator = DefaultRoomCodeAllocator(Random(7), NoOpRoomStore, NoOpStandingTableRepository)
        repeat(DefaultRoomCodeAllocator.REMEMBERED_CODE_LIMIT + 50) { allocator.withCode { it } }
        assertEquals(DefaultRoomCodeAllocator.REMEMBERED_CODE_LIMIT, allocator.rememberedCodeCount)
    }
    @Test fun aFailedUniquenessCheckFailsTheCreateInsteadOfHandingOutAnUncheckedCode() = runTest {
        val store = object : RoomStore by NoOpRoomStore { override suspend fun codeTaken(code: String) = error("Database unavailable") }
        var created = false
        val allocator = DefaultRoomCodeAllocator(Random(0), store, NoOpStandingTableRepository)
        assertFailsWith<IllegalStateException> { allocator.withCode { created = true } }
        assertFalse(created)
    }
}
