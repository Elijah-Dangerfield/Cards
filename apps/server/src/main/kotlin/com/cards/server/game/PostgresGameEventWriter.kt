package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameEventEnvelopeJson
import com.dangerfield.cards.libraries.gameplay.toEnvelope
import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.GameEventsTable
import com.dangerfield.cards.server.di.ServerScope
import kotlinx.serialization.encodeToString
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.insert
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.UUID

/**
 * Exposed-backed [GameEventWriter]. One row per event; the composite
 * PK `(session_id, seq)` enforces append idempotency at the DB
 * boundary — a retried write with the same `(sessionId, sequence)`
 * pair races to constraint-violation, which is the correct failure
 * shape (the row exists; the caller's broadcast already ran or is
 * about to).
 *
 * The whole batch lands in one transaction so an intent that produces
 * N events is all-or-nothing — partial-event persistence would leave
 * the durable log in a state the engine can't replay.
 *
 * Persisted body is the v1 [com.dangerfield.cards.libraries.gameplay.GameEventEnvelope]
 * (libraries/gameplay). `event_type` mirrors the kotlinx `@SerialName`
 * so SQL filters can pick out specific event types without
 * unmarshalling JSONB.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class PostgresGameEventWriter(
    private val database: Database,
) : GameEventWriter {

    override suspend fun append(sessionId: UUID, events: List<GameEvent>) {
        if (events.isEmpty()) return
        database.transaction {
            events.forEach { event ->
                val envelope = event.toEnvelope()
                val payload = GameEventEnvelopeJson.encodeToString(envelope)
                GameEventsTable.insert {
                    it[GameEventsTable.sessionId] = sessionId
                    it[GameEventsTable.seq] = event.sequence
                    it[GameEventsTable.eventType] = envelope.type
                    it[GameEventsTable.eventJsonb] = payload
                }
            }
        }
    }
}
