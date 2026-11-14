package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.rooms.StandingTable
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class StandingTableCacheTest : CoroutineTest() {
    @Test
    fun accountChangeClearsNamesAndDiscardsLateRefresh() = runUnitTest {
        val cache = StandingTableCache()
        val previousGeneration = cache.beginRefresh()
        cache.replaceIfCurrent(previousGeneration, listOf(table("Private table")))
        assertEquals(1, cache.mine.value.size)
        cache.clear("old-user")
        assertEquals(emptyList(), cache.mine.value)
        cache.replaceIfCurrent(previousGeneration, listOf(table("Old account response")))
        assertEquals(emptyList(), cache.mine.value)
        cache.replaceIfCurrent(cache.beginRefresh(), listOf(table("New account table")))
        assertEquals("New account table", cache.mine.value.single().name)
    }

    private fun table(name: String) = StandingTable(
        "K7M2QX", name, "owner", "Alex", true, 5000, 6,
        DayOfWeek.THURSDAY, LocalTime(20, 0), TimeZone.of("America/New_York"),
        120, Instant.parse("2026-11-20T01:00:00Z"), 1,
    )
}
