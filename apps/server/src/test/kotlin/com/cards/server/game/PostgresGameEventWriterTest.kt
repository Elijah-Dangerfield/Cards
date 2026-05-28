package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameEventEnvelope
import com.dangerfield.cards.libraries.gameplay.GameEventEnvelopeJson
import com.dangerfield.cards.libraries.gameplay.toEnvelope
import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.GameEventsTable
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.junit.After
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round-trip pin for the Postgres-backed event writer. Verifies that
 * the durable JSONB row written here decodes back through the v1
 * envelope into a `GameEvent` equal to what we appended — that's the
 * invariant the future replay-from-log path (B1+) leans on. Also pins
 * the `(session_id, seq)` PK as the idempotency seal.
 *
 * The kotlinx polymorphic JSON used by `toEnvelope`'s output is loaded
 * via [GameEventEnvelopeJson] so the test mirrors the production
 * encoder exactly.
 */
class PostgresGameEventWriterTest : DatabaseTest() {

    @After
    fun cleanTables() {
        database.blockingTransaction {
            GameEventsTable.deleteAll()
        }
    }

    private fun newWriter(): PostgresGameEventWriter = PostgresGameEventWriter(database)

    @Test
    fun append_persistsEvent_andRoundTripsThroughEnvelope() = runTest {
        val writer = newWriter()
        val sessionId = UUID.randomUUID()
        val handStarted = GameEvent.HandStarted(sequence = 0, handNumber = 1, buttonSeatIndex = 0)
        val blindSmall = GameEvent.BlindPosted(sequence = 1, seatIndex = 0, amount = 5, isSmall = true)

        writer.append(sessionId, listOf(handStarted, blindSmall))

        val rows = database.transaction {
            GameEventsTable
                .selectAll()
                .where { GameEventsTable.sessionId eq sessionId }
                .orderBy(GameEventsTable.seq)
                .map { it }
        }
        assertEquals(2, rows.size)

        val first = rows[0]
        assertEquals(0L, first[GameEventsTable.seq])
        assertEquals("HandStarted", first[GameEventsTable.eventType])
        val firstEnvelope = decode(first[GameEventsTable.eventJsonb])
        assertEquals(1, firstEnvelope.version)
        assertEquals(handStarted.toEnvelope(), firstEnvelope)

        val second = rows[1]
        assertEquals(1L, second[GameEventsTable.seq])
        assertEquals("BlindPosted", second[GameEventsTable.eventType])
        assertEquals(blindSmall.toEnvelope(), decode(second[GameEventsTable.eventJsonb]))
    }

    @Test
    fun append_emptyBatch_isNoOp() = runTest {
        val writer = newWriter()
        val sessionId = UUID.randomUUID()

        writer.append(sessionId, emptyList())

        val count = database.transaction {
            GameEventsTable
                .selectAll()
                .where { GameEventsTable.sessionId eq sessionId }
                .count()
        }
        assertEquals(0, count)
    }

    @Test
    fun append_writesDistinctSessionsIndependently() = runTest {
        val writer = newWriter()
        val sessionA = UUID.randomUUID()
        val sessionB = UUID.randomUUID()
        val event = GameEvent.StreetAdvanced(
            sequence = 0,
            street = BettingRound.Flop,
            communityCards = emptyList(),
        )
        writer.append(sessionA, listOf(event))
        writer.append(sessionB, listOf(event))

        val aCount = database.transaction {
            GameEventsTable.selectAll().where { GameEventsTable.sessionId eq sessionA }.count()
        }
        val bCount = database.transaction {
            GameEventsTable.selectAll().where { GameEventsTable.sessionId eq sessionB }.count()
        }
        assertEquals(1, aCount)
        assertEquals(1, bCount)
    }

    private fun decode(raw: String): GameEventEnvelope =
        GameEventEnvelopeJson.decodeFromString(GameEventEnvelope.serializer(), raw)
}
