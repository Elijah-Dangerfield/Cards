package com.dangerfield.cards.libraries.cards.impl.logging

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized

/**
 * Bounded, thread-safe in-memory ring of recent log lines. Holds the last
 * [capacity] entries, evicting oldest-first, and caps each line at
 * [maxLineChars] so one runaway message can't blow the dump size.
 *
 * Backs [SentryLogTree]'s feedback buffer: it retains the fine-grained logs we
 * don't ship, and [snapshot] dumps them onto a user-feedback event. Logs
 * arrive from many threads, so writes are synchronized.
 */
@OptIn(InternalCoroutinesApi::class)
internal class LogRingBuffer(
    private val capacity: Int,
    private val maxLineChars: Int,
) {
    private val lock = SynchronizedObject()
    private val lines = ArrayDeque<String>()

    fun add(line: String) {
        val capped = if (line.length > maxLineChars) line.substring(0, maxLineChars) + "…" else line
        synchronized(lock) {
            if (lines.size >= capacity) lines.removeFirst()
            lines.addLast(capped)
        }
    }

    /** Newline-joined snapshot of the retained lines, oldest first. Non-clearing. */
    fun snapshot(): String = synchronized(lock) { lines.joinToString("\n") }
}
