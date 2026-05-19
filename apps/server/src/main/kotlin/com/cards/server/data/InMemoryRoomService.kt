package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.CreateResult
import com.dangerfield.cards.server.domain.JoinResult
import com.dangerfield.cards.server.domain.LeaveResult
import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomMember
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.RoomStatus
import com.dangerfield.cards.server.domain.RoomSweepResult
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
import kotlin.time.Duration
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
 *
 * Reconnect grace: a member's `disconnectedAt` field stamps the moment
 * their socket dropped (or, for fresh joins, the join time itself). The
 * sweep [sweepDisconnected] reaps anyone past the configured TTL,
 * freeing their seat and (if it empties the room) GC'ing the room. The
 * sweep is exposed via `POST /v1/admin/sweep-disconnected-room-members`
 * and triggered on a cron, matching the orphan-anon-sweep pattern.
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

    override suspend fun create(hostUserId: UserId, hostName: String, maxSeats: Int): CreateResult = mutex.withLock {
        val activeHosted = rooms.values.count { it.room.hostUserId == hostUserId }
        if (activeHosted >= RoomService.MAX_ROOMS_PER_HOST) {
            // Soft cap — see `RoomService.MAX_ROOMS_PER_HOST`. Honest
            // workflows can always leave a prior room and try again; the
            // sweep will eventually free abandoned ones too.
            return@withLock CreateResult.TooManyRooms(activeCount = activeHosted)
        }
        val now = clock.now()
        val code = generateUniqueCode()
        val host = RoomMember(
            userId = hostUserId,
            displayName = hostName,
            seatIndex = 0,
            joinedAt = now,
            isConnected = false,
            // Stamped at join so the sweep treats "never-connected" the
            // same as "disconnected": the seat-grace clock starts now.
            // Cleared by markConnected(true) when the socket opens.
            disconnectedAt = now,
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
        CreateResult.Success(room)
    }

    override suspend fun join(code: String, userId: UserId, name: String): JoinResult = mutex.withLock {
        val state = rooms[code] ?: return@withLock JoinResult.RoomNotFound
        val current = state.room

        current.memberFor(userId)?.let { return@withLock JoinResult.AlreadyJoined(current) }
        if (current.status != RoomStatus.Lobby) return@withLock JoinResult.NotJoinable(current.status)
        if (current.isFull) return@withLock JoinResult.Full

        val seatIndex = nextFreeSeat(current)
        val now = clock.now()
        val newMember = RoomMember(
            userId = userId,
            displayName = name,
            seatIndex = seatIndex,
            joinedAt = now,
            isConnected = false,
            // See `create` — stamp disconnectedAt so the sweep treats
            // "joined-but-never-opened-a-socket" the same as a clean drop.
            disconnectedAt = now,
        )
        val next = current.copy(members = (current.members + newMember).sortedBy { it.seatIndex })
        state.update(next)
        JoinResult.Success(next)
    }

    override suspend fun leave(code: String, userId: UserId): LeaveResult = mutex.withLock {
        val state = rooms[code] ?: return@withLock LeaveResult.RoomNotFound
        val current = state.room
        if (current.memberFor(userId) == null) return@withLock LeaveResult.NotInRoom

        val remaining = current.members.filterNot { it.userId == userId }
        if (remaining.isEmpty()) {
            // Last one out kills the lights. Drop the flow so any
            // stragglers observing get a final value through the
            // GC sweep on the caller side.
            rooms.remove(code)
            return@withLock LeaveResult.Success(roomGone = true)
        }

        // Host migration: if the host is the one leaving, promote the
        // longest-tenured remaining member (oldest `joinedAt`, ties
        // broken by lowest seat). Without this the room would keep a
        // stale `hostUserId` pointing at someone who's no longer a
        // member — start-game permissions break, the UI shows a ghost
        // host, the next leave loops.
        val nextHostUserId = if (current.hostUserId == userId) {
            remaining.minWith(compareBy({ it.joinedAt }, { it.seatIndex })).userId
        } else {
            current.hostUserId
        }

        val next = current.copy(
            hostUserId = nextHostUserId,
            members = remaining,
        )
        state.update(next)
        LeaveResult.Success(roomGone = false)
    }

    override suspend fun markConnected(code: String, userId: UserId, connected: Boolean): Room? = mutex.withLock {
        val state = rooms[code] ?: return@withLock null
        val current = state.room
        val member = current.memberFor(userId) ?: return@withLock current
        if (member.isConnected == connected) return@withLock current
        val now = clock.now()
        val next = current.copy(
            members = current.members.map { m ->
                if (m.userId == userId) {
                    // Stamp disconnectedAt on disconnect; clear it on reconnect.
                    // The sweep reads this stamp to decide grace-window expiry.
                    m.copy(
                        isConnected = connected,
                        disconnectedAt = if (connected) null else now,
                    )
                } else m
            },
        )
        state.update(next)
        next
    }

    override suspend fun find(code: String): Room? = mutex.withLock { rooms[code]?.room }

    override suspend fun observe(code: String): Flow<Room>? = mutex.withLock {
        rooms[code]?.flow?.asStateFlow()
    }

    override suspend fun snapshot(): List<Room> = mutex.withLock {
        // Stable order by creation time keeps the admin dashboard scan
        // predictable; ties (same instant from FixedClock) fall back to
        // code so the output is fully deterministic.
        rooms.values.map { it.room }
            .sortedWith(compareBy({ it.createdAt }, { it.code }))
    }

    override suspend fun sweepDisconnected(maxIdle: Duration): RoomSweepResult = mutex.withLock {
        val cutoff = clock.now() - maxIdle
        val roomsSeen = rooms.size
        var membersReaped = 0
        var roomsReaped = 0

        // Iterate via a snapshot of entries — we may remove entries mid-loop.
        val codes = rooms.keys.toList()
        for (code in codes) {
            val state = rooms[code] ?: continue
            val current = state.room
            // A member is sweepable iff they're currently disconnected
            // AND the stamp on the disconnect is older than the cutoff.
            // Note: create() and join() both stamp disconnectedAt = now,
            // so "joined-but-never-opened-a-socket" is treated the same
            // as a clean drop — both age into sweep eligibility at the
            // same rate. The null-check is belt-and-braces; markConnected
            // is the only path that clears the field.
            val toReap = current.members.filter { member ->
                val droppedAt = member.disconnectedAt
                !member.isConnected && droppedAt != null && droppedAt <= cutoff
            }
            if (toReap.isEmpty()) continue

            val survivors = current.members - toReap.toSet()
            membersReaped += toReap.size
            if (survivors.isEmpty()) {
                // Sweep emptied the room. GC same as last-out leave().
                rooms.remove(code)
                roomsReaped++
                // No flow update — the room is gone and any straggling
                // subscribers will simply stop receiving emissions.
            } else {
                state.update(current.copy(members = survivors))
            }
        }
        RoomSweepResult(
            membersReaped = membersReaped,
            roomsReaped = roomsReaped,
            roomsSeen = roomsSeen,
        )
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
