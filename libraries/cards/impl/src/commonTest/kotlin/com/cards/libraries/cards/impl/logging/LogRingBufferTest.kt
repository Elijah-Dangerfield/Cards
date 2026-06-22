package com.dangerfield.cards.libraries.cards.impl.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogRingBufferTest {

    @Test
    fun snapshot_isEmpty_whenNothingAdded() {
        val buffer = LogRingBuffer(capacity = 4, maxLineChars = 100)
        assertEquals("", buffer.snapshot())
    }

    @Test
    fun snapshot_returnsLines_oldestFirst() {
        val buffer = LogRingBuffer(capacity = 4, maxLineChars = 100)
        buffer.add("a")
        buffer.add("b")
        buffer.add("c")
        assertEquals("a\nb\nc", buffer.snapshot())
    }

    @Test
    fun add_evictsOldest_pastCapacity() {
        val buffer = LogRingBuffer(capacity = 3, maxLineChars = 100)
        listOf("1", "2", "3", "4", "5").forEach(buffer::add)
        // Only the last 3 survive, in order.
        assertEquals("3\n4\n5", buffer.snapshot())
    }

    @Test
    fun add_capsLongLines() {
        val buffer = LogRingBuffer(capacity = 4, maxLineChars = 5)
        buffer.add("abcdefghij")
        val only = buffer.snapshot()
        assertEquals("abcde…", only)
        assertTrue(only.endsWith("…"), "over-long line should be truncated with an ellipsis")
    }
}
