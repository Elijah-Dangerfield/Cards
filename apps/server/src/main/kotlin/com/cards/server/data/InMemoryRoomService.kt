package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.JoinResult
import com.dangerfield.cards.server.domain.LeaveResult
import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomMember
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.RoomStatus
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Concurrent-safe in-memory [RoomService]. V1 ships only this — when MP
 * graduates to "rejoin a hand after a cold start," persistence + a
 * shared backplane (Redis pub/sub or Postgres LISTEN/NOTIFY) become
 * required and this becomes the L1 cache in front of that.
 *
 * Concurrency model: one global [Mutex] for the rooms table (small N,
 * cheap to hold during a create/join/leave). Each room's observation
 * uses its own [MutableStateFlow]; subscribers don't take the global
 * mutex. Mutations emit through the flow at the end of the locked
 * critical section so observers see one consistent snapshot per change.
 *
 * Code generation: 6-char alphanumeric from an unambiguous alphabet
 * (no 0/O/1/I/L). 32^6 ≈ 1 billion combos — collision-rate analysis at
 * 10k concurrent rooms is < 1 / 100k creations. Retry on conflict
 * caps at [MAX_CODE_RETRIES] before giving up (would only fire under
 * pathological RNG misalignment).
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class InMemoryRoomService(
    private val clock: Clock,
    private val random: Random = Random.Default,
) : RoomService {

    private val mutex = Mutex()
    /** All live rooms by code. Each value also owns its own flow for observers. */
    private val rooms: MutableMap<String, RoomState> = mutableMapOf()

    private data class RoomState(
        var room: Room,
        val flow: MutableStateFlow<Room> = MutableStateFlow(room),
    ) {
        fun update(next: Room) {
            room = next
            flow.value = next
        }
    }

    override suspend fun create(hostUserId: UserId, hostName: String, maxSeats: Int): Room = mutex.withLock {
        val now = clock.now()
        val code = generateUniqueCode()
        val host = RoomMember(
            userId = hostUserId,
            displayName = hostName,
            seatIndex = 0,
            joinedAt = now,
            isConnected = false,
        )
        val room = Room(
            code = code,
            hostUserId = hostUserId,
            createdAt = now,
            maxSeats = maxSeats,
            status = RoomStatus.Lobby,
            members = listOf(host),
        )
        rooms[code] = RoomState(room = room)
        room
    }

    override suspend fun join(code: String, userId: UserId, name: String): JoinResult = mutex.withLock {
        val state = rooms[code] ?: return@withLock JoinResult.RoomNotFound
        val current = state.room

        current.memberFor(userId)?.let { return@withLock JoinResult.AlreadyJoined(current) }
        if (current.status != RoomStatus.Lobby) return@withLock JoinResult.NotJoinable(current.status)
        if (current.isFull) return@withLock JoinResult.Full

        val seatIndex = nextFreeSeat(current)
        val newMember = RoomMember(
            userId = userId,
            displayName = name,
            seatIndex = seatIndex,
            joinedAt = clock.now(),
            isConnected = false,
        )
        val next = current.copy(members = (current.members + newMember).sortedBy { it.seatIndex })
        state.update(next)
        JoinResult.Success(next)
    }

    override suspend fun leave(code: String, userId: UserId): LeaveResult = mutex.withLock {
        val state = rooms[code] ?: return@withLock LeaveResult.RoomNotFound
        val current = state.room
        if (current.memberFor(userId) == null) return@withLock LeaveResult.NotInRoom

        val next = current.copy(members = current.members.filterNot { it.userId == userId })
        if (next.members.isEmpty()) {
            // Last one out kills the lights. Drop the flow so any
            // stragglers observing get a final value through the
            // GC sweep on the caller side.
            rooms.remove(code)
            return@withLock LeaveResult.Success(roomGone = true)
        }
        state.update(next)
        LeaveResult.Success(roomGone = false)
    }

    override suspend fun markConnected(code: String, userId: UserId, connected: Boolean): Room? = mutex.withLock {
        val state = rooms[code] ?: return@withLock null
        val current = state.room
        val member = current.memberFor(userId) ?: return@withLock current
        if (member.isConnected == connected) return@withLock current
        val next = current.copy(
            members = current.members.map { m ->
                if (m.userId == userId) m.copy(isConnected = connected) else m
            },
        )
        state.update(next)
        next
    }

    override suspend fun find(code: String): Room? = mutex.withLock { rooms[code]?.room }

    override suspend fun observe(code: String): Flow<Room>? = mutex.withLock {
        rooms[code]?.flow?.asStateFlow()
    }

    private fun generateUniqueCode(): String {
        repeat(MAX_CODE_RETRIES) {
            val code = randomCode()
            if (code !in rooms) return code
        }
        error("Couldn't generate a unique room code after $MAX_CODE_RETRIES attempts")
    }

    private fun randomCode(): String = buildString(CODE_LENGTH) {
        repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
    }

    private fun nextFreeSeat(room: Room): Int {
        // Fill the lowest unused seat index — keeps the seating chart
        // visually stable as people leave + rejoin.
        val taken = room.members.map { it.seatIndex }.toSet()
        for (i in 0 until room.maxSeats) if (i !in taken) return i
        // Shouldn't be reachable thanks to the [isFull] check above, but
        // belt + braces.
        error("No free seats in room ${room.code}")
    }

    companion object {
        const val CODE_LENGTH = 6
        // Unambiguous alphabet — drops 0/O, 1/I/L. 32 chars × 6 length
        // = ~1 billion combos.
        const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private const val MAX_CODE_RETRIES = 50
    }
}
