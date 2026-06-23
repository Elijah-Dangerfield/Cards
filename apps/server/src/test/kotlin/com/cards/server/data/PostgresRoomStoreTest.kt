package com.dangerfield.cards.server.data

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.domain.BotSeat
import com.dangerfield.cards.server.domain.CreateResult
import com.dangerfield.cards.server.domain.JoinResult
import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomMember
import com.dangerfield.cards.server.domain.RoomStatus
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * B2 durability. Pins that [PostgresRoomStore] round-trips a room (humans + a
 * bot seat) and — the load-bearing guarantee — that an [InMemoryRoomService]
 * backed by it surfaces a room created by a *previous* service instance, i.e.
 * room codes + membership survive a server restart.
 */
@OptIn(ExperimentalTime::class)
class PostgresRoomStoreTest : DatabaseTest() {

    private val fixedNow = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val clock = object : Clock { override fun now(): Instant = fixedNow }

    private fun store() = PostgresRoomStore(database)

    @Test
    fun save_thenLoad_roundTripsRoomWithHumanAndBotSeats() = runTest {
        val store = store()
        val code = "ROOM" + UUID.randomUUID().toString().take(2).uppercase()
        val host = UserId(UUID.randomUUID())
        val room = Room(
            code = code,
            hostUserId = host,
            createdAt = fixedNow,
            maxSeats = 6,
            status = RoomStatus.Lobby,
            buyIn = 500,
            members = listOf(
                RoomMember(
                    userId = host,
                    displayName = "Alice",
                    seatIndex = 0,
                    joinedAt = fixedNow,
                    isConnected = true,
                    disconnectedAt = null,
                    avatarEmoji = "🎩",
                    avatarBackgroundColor = "#112233",
                ),
                RoomMember(
                    userId = UserId(UUID.randomUUID()),
                    displayName = "Jane",
                    seatIndex = 1,
                    joinedAt = fixedNow,
                    isConnected = true,
                    disconnectedAt = null,
                    avatarEmoji = "🤖",
                    bot = BotSeat(BotPersonality.Jane, BotDifficulty.Standard, revealed = true),
                ),
            ),
        )

        store.save(room)
        val loaded = store.load(code)!!

        assertEquals(code, loaded.code)
        assertEquals(host, loaded.hostUserId)
        assertEquals(RoomStatus.Lobby, loaded.status)
        assertEquals(500, loaded.buyIn)
        assertEquals(6, loaded.maxSeats)
        assertEquals(2, loaded.members.size)
        val botSeat = loaded.members.first { it.seatIndex == 1 }
        assertTrue(botSeat.isBot, "the bot seat round-trips as a bot")
        assertEquals("Jane", botSeat.bot!!.personality.name)
        assertEquals(BotDifficulty.Standard, botSeat.bot!!.difficulty)
        // Hydrated members come back disconnected — sockets re-establish presence.
        assertTrue(loaded.members.all { !it.isConnected })
    }

    @Test
    fun save_replacesMemberSet_onSecondWrite() = runTest {
        val store = store()
        val code = "RPL" + UUID.randomUUID().toString().take(3).uppercase()
        val host = UserId(UUID.randomUUID())
        fun member(id: UserId, seat: Int, name: String) = RoomMember(
            userId = id, displayName = name, seatIndex = seat, joinedAt = fixedNow,
            isConnected = false, disconnectedAt = fixedNow,
        )
        val base = Room(code, host, fixedNow, 6, RoomStatus.Lobby, listOf(member(host, 0, "Alice")))
        store.save(base)
        val joiner = UserId(UUID.randomUUID())
        store.save(base.copy(members = listOf(member(host, 0, "Alice"), member(joiner, 1, "Bob"))))
        assertEquals(2, store.load(code)!!.members.size)
        // Leaving back to one member must not leave an orphan seat behind.
        store.save(base.copy(members = listOf(member(host, 0, "Alice"))))
        assertEquals(1, store.load(code)!!.members.size)
    }

    @Test
    fun delete_removesRoomAndMembers() = runTest {
        val store = store()
        val code = "DEL" + UUID.randomUUID().toString().take(3).uppercase()
        val host = UserId(UUID.randomUUID())
        store.save(
            Room(code, host, fixedNow, 6, RoomStatus.Lobby, listOf(
                RoomMember(host, "Alice", 0, fixedNow, isConnected = false, disconnectedAt = fixedNow),
            )),
        )
        store.delete(code)
        assertNull(store.load(code))
    }

    @Test
    fun roomCodeSurvivesRestart_aFreshServiceHydratesFromStore() = runTest {
        val store = store()
        val host = UserId(UUID.randomUUID())
        val joiner = UserId(UUID.randomUUID())

        // "Before the restart": one service writes through to Postgres.
        val before = InMemoryRoomService(clock = clock, random = Random(0L), store = store)
        val created = before.create(hostUserId = host, hostName = "Alice", buyIn = 500) as CreateResult.Success
        val code = created.room.code
        val joined = before.join(code, joiner, "Bob")
        assertTrue(joined is JoinResult.Success)
        before.markPlaying(code)

        // "After the restart": a brand-new service with an empty cache must
        // recover the room — code, both members, and the Playing status.
        val after = InMemoryRoomService(clock = clock, random = Random(0L), store = store)
        val recovered = after.find(code)!!
        assertEquals(code, recovered.code)
        assertEquals(host, recovered.hostUserId)
        assertEquals(RoomStatus.Playing, recovered.status)
        assertEquals(setOf(host, joiner), recovered.members.map { it.userId }.toSet())
    }

    @Test
    fun leaveLastMember_clearsDurableRoom_soRestartDoesNotResurrectIt() = runTest {
        val store = store()
        val host = UserId(UUID.randomUUID())
        val before = InMemoryRoomService(clock = clock, random = Random(0L), store = store)
        val code = (before.create(hostUserId = host, hostName = "Solo") as CreateResult.Success).room.code
        before.leave(code, host) // last member out → room GC'd

        val after = InMemoryRoomService(clock = clock, random = Random(0L), store = store)
        assertNull(after.find(code), "a room emptied before restart must not resurrect")
    }
}
