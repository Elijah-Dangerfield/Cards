package com.dangerfield.cards.server.data

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.RoomMembersTable
import com.dangerfield.cards.server.db.RoomsTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.BotSeat
import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomMember
import com.dangerfield.cards.server.domain.RoomStatus
import com.dangerfield.cards.server.domain.RoomStore
import com.dangerfield.cards.server.domain.RoomVisibility
import com.dangerfield.cards.server.domain.UserId
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.ExperimentalTime

/**
 * Exposed-backed [RoomStore]. Each [save] is a whole-room upsert inside one
 * transaction: upsert the `rooms` row, then replace the `room_members` set
 * (delete-all + re-insert). Member counts are tiny (≤ 6), so the replace is
 * cheaper than diffing and keeps the snapshot semantics dead simple — what's in
 * memory is exactly what lands in Postgres.
 *
 * Status is stored lowercase to match the existing string-enum convention
 * (friend_relations, inventory.acquisition_source). Bot seats round-trip through
 * the `bot_*` columns; personality is rebuilt by name from [BotPersonality.Roster]
 * (a name no longer in the roster falls back to a roster entry so a hydrated bot
 * still plays — same spirit as the driver's restart fallback).
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresRoomStore(
    private val database: Database,
) : RoomStore {

    override suspend fun save(room: Room) {
        database.transaction {
            val rowsUpdated = RoomsTable.update({ RoomsTable.code eq room.code }) {
                it[hostUserId] = room.hostUserId.value
                it[status] = room.status.toDb()
                it[buyIn] = room.buyIn
                it[maxSeats] = room.maxSeats
                it[createdAt] = room.createdAt.toJavaInstant()
                it[visibility] = room.visibility.toDb()
            }
            if (rowsUpdated == 0) {
                RoomsTable.insert {
                    it[code] = room.code
                    it[hostUserId] = room.hostUserId.value
                    it[status] = room.status.toDb()
                    it[buyIn] = room.buyIn
                    it[maxSeats] = room.maxSeats
                    it[createdAt] = room.createdAt.toJavaInstant()
                    it[visibility] = room.visibility.toDb()
                }
            }
            // Replace the member set wholesale — snapshot semantics.
            RoomMembersTable.deleteWhere { RoomMembersTable.roomCode eq room.code }
            for (member in room.members) {
                RoomMembersTable.insert {
                    it[roomCode] = room.code
                    it[userId] = member.userId.value
                    it[seatIndex] = member.seatIndex
                    it[displayName] = member.displayName
                    it[joinedAt] = member.joinedAt.toJavaInstant()
                    it[disconnectedAt] = member.disconnectedAt?.toJavaInstant()
                    it[avatarEmoji] = member.avatarEmoji
                    it[avatarBackgroundColor] = member.avatarBackgroundColor
                    it[botPersonality] = member.bot?.personality?.name
                    it[botDifficulty] = member.bot?.difficulty?.name
                    it[botRevealed] = member.bot?.revealed
                }
            }
        }
    }

    override suspend fun delete(code: String) {
        database.transaction {
            // Members cascade on the FK, but delete them explicitly too so a
            // mid-cascade failure can't strand orphan seats.
            RoomMembersTable.deleteWhere { RoomMembersTable.roomCode eq code }
            RoomsTable.deleteWhere { RoomsTable.code eq code }
        }
    }

    override suspend fun load(code: String): Room? = database.transaction {
        val roomRow = RoomsTable
            .selectAll()
            .where { RoomsTable.code eq code }
            .singleOrNull()
            ?: return@transaction null

        val members = RoomMembersTable
            .selectAll()
            .where { RoomMembersTable.roomCode eq code }
            .map { row ->
                val personalityName = row[RoomMembersTable.botPersonality]
                RoomMember(
                    userId = UserId(row[RoomMembersTable.userId]),
                    displayName = row[RoomMembersTable.displayName],
                    seatIndex = row[RoomMembersTable.seatIndex],
                    joinedAt = row[RoomMembersTable.joinedAt].toKotlinInstant(),
                    isConnected = false, // sockets are re-established after a restart
                    disconnectedAt = row[RoomMembersTable.disconnectedAt]?.toKotlinInstant(),
                    avatarEmoji = row[RoomMembersTable.avatarEmoji],
                    avatarBackgroundColor = row[RoomMembersTable.avatarBackgroundColor],
                    bot = personalityName?.let { name ->
                        BotSeat(
                            personality = personalityForName(name),
                            difficulty = row[RoomMembersTable.botDifficulty]
                                ?.let { runCatching { BotDifficulty.valueOf(it) }.getOrNull() }
                                ?: BotDifficulty.Standard,
                            revealed = row[RoomMembersTable.botRevealed] ?: true,
                        )
                    },
                )
            }
            .sortedBy { it.seatIndex }

        Room(
            code = roomRow[RoomsTable.code],
            hostUserId = UserId(roomRow[RoomsTable.hostUserId]),
            createdAt = roomRow[RoomsTable.createdAt].toKotlinInstant(),
            maxSeats = roomRow[RoomsTable.maxSeats],
            status = roomRow[RoomsTable.status].toRoomStatus(),
            members = members,
            buyIn = roomRow[RoomsTable.buyIn],
            visibility = roomRow[RoomsTable.visibility].toVisibility(),
        )
    }

    private fun personalityForName(name: String): BotPersonality =
        BotPersonality.Roster.firstOrNull { it.name == name } ?: BotPersonality.Roster.first()

    private fun RoomStatus.toDb(): String = when (this) {
        RoomStatus.Lobby -> "lobby"
        RoomStatus.Playing -> "playing"
        RoomStatus.Finished -> "finished"
    }

    private fun String.toRoomStatus(): RoomStatus = when (this) {
        "lobby" -> RoomStatus.Lobby
        "playing" -> RoomStatus.Playing
        "finished" -> RoomStatus.Finished
        else -> RoomStatus.Lobby
    }

    private fun RoomVisibility.toDb(): String = when (this) {
        RoomVisibility.Private -> "private"
        RoomVisibility.Open -> "open"
        RoomVisibility.Public -> "public"
    }

    private fun String.toVisibility(): RoomVisibility = when (this) {
        "private" -> RoomVisibility.Private
        "open" -> RoomVisibility.Open
        "public" -> RoomVisibility.Public
        else -> RoomVisibility.Private
    }
}
