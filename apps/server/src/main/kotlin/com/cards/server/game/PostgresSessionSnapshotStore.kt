package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.RoomSessionsTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Exposed-backed [SessionSnapshotStore]. Round-trips [GameState] through
 * the project's kotlinx-serialization `Json` — same encoder the wire
 * format already uses, so the engine's existing round-trip tests pin
 * shape stability.
 *
 * Upsert is two-statement: try UPDATE first (the common path while a
 * room is live and re-mutating into the same row), fall back to INSERT
 * on a zero-row update (first write for a new session). Both run inside
 * one transaction so a concurrent registry creating the same session
 * can't slip between them — Postgres' REPEATABLE READ isolation
 * surfaces that race as a serialization failure and the caller retries.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class PostgresSessionSnapshotStore(
    private val database: Database,
) : SessionSnapshotStore {

    private val json: Json = Json

    override suspend fun upsert(snapshot: SessionSnapshot) {
        val encoded = json.encodeToString(GameState.serializer(), snapshot.state)
        val javaUpdatedAt = snapshot.updatedAt.toJavaInstant()
        database.transaction {
            val rowsUpdated = RoomSessionsTable.update(
                { RoomSessionsTable.sessionId eq snapshot.sessionId },
            ) {
                it[stateJsonb] = encoded
                it[updatedAt] = javaUpdatedAt
            }
            if (rowsUpdated == 0) {
                RoomSessionsTable.insert {
                    it[sessionId] = snapshot.sessionId
                    it[roomCode] = snapshot.code
                    it[stateJsonb] = encoded
                    it[updatedAt] = javaUpdatedAt
                }
            }
        }
    }

    override suspend fun readByCode(code: String): SessionSnapshot? = database.transaction {
        RoomSessionsTable
            .selectAll()
            .where { RoomSessionsTable.roomCode eq code }
            .singleOrNull()
            ?.let { row ->
                SessionSnapshot(
                    sessionId = row[RoomSessionsTable.sessionId],
                    code = row[RoomSessionsTable.roomCode],
                    state = json.decodeFromString(
                        GameState.serializer(),
                        row[RoomSessionsTable.stateJsonb],
                    ),
                    updatedAt = row[RoomSessionsTable.updatedAt].toKotlinInstant(),
                )
            }
    }

    override suspend fun deleteByCode(code: String) {
        database.transaction {
            RoomSessionsTable.deleteWhere { RoomSessionsTable.roomCode eq code }
        }
    }
}
