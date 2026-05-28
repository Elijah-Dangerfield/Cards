package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.GameEvent
import java.util.UUID

/**
 * Persists the events emitted by a [GameSession] to a durable log
 * before the in-memory broadcast lands. Lives behind DI so production
 * binds the Postgres-backed impl while tests that don't care about
 * persistence stay on [NoOp].
 *
 * **Contract:** `append` must persist every event in [events] durably
 * before returning, atomically as a batch — a single intent that
 * produces N events is one all-or-nothing transaction. [GameSession]
 * calls this inside its per-session mutex and only mutates its
 * `_state` + broadcasts to `_events` if `append` succeeds. That
 * ordering is the invariant the event-sourced architecture leans on
 * (docs/decisions.md 2026-05-27, docs/todo.md §B0).
 *
 * Throwing from `append` aborts the action — [GameSession] turns the
 * failure into [IntentResult.Rejected] without touching memory. Don't
 * swallow failures here.
 */
interface GameEventWriter {
    suspend fun append(sessionId: UUID, events: List<GameEvent>)

    /**
     * No-op writer for unit-test surfaces (the legacy
     * `InMemoryGameSessionRegistry()` constructions in route tests, the
     * engine-focused [GameSessionTest] cases that don't pin
     * persistence). Production uses the DI-bound Postgres impl.
     */
    object NoOp : GameEventWriter {
        override suspend fun append(sessionId: UUID, events: List<GameEvent>) = Unit
    }
}
