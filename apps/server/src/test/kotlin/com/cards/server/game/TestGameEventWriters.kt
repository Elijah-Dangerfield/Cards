package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.GameEvent
import java.util.UUID

/**
 * Captures every append into an in-memory list so tests can pin
 * write-through behaviour. Lives in the test-package scope so the
 * session-level and registry-level test files can share the helper.
 */
internal class RecordingGameEventWriter : GameEventWriter {
    private val rows = mutableListOf<Pair<UUID, GameEvent>>()
    override suspend fun append(sessionId: UUID, events: List<GameEvent>) {
        events.forEach { rows += sessionId to it }
    }
    fun appendedEvents(sessionId: UUID): List<GameEvent> =
        rows.filter { it.first == sessionId }.map { it.second }
}

/** Always throws — pins the "writer-failure leaves state unchanged" invariant. */
internal class ExplodingGameEventWriter : GameEventWriter {
    override suspend fun append(sessionId: UUID, events: List<GameEvent>) {
        throw RuntimeException("DB is down")
    }
}

/**
 * Pass-through until [fail] flips, then throws on every subsequent
 * append. Lets a test stand up a real `GameSession` and then simulate
 * a writer failure on the next intent without rebuilding the session.
 */
internal class FlippableGameEventWriter : GameEventWriter {
    @Volatile var fail: Boolean = false
    override suspend fun append(sessionId: UUID, events: List<GameEvent>) {
        if (fail) throw RuntimeException("DB is down")
    }
}
