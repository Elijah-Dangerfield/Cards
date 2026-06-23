package com.dangerfield.cards.server.data

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.AddBotResult
import com.dangerfield.cards.server.domain.BotSeat
import com.dangerfield.cards.server.domain.BuyInTier
import com.dangerfield.cards.server.domain.CreateResult
import com.dangerfield.cards.server.domain.JoinResult
import com.dangerfield.cards.server.domain.LeaveResult
import com.dangerfield.cards.server.domain.MatchmakingResult
import com.dangerfield.cards.server.domain.RemoveBotResult
import com.dangerfield.cards.server.domain.NoOpRoomStore
import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomClosedListener
import com.dangerfield.cards.server.domain.RoomMember
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.RoomStatus
import com.dangerfield.cards.server.domain.RoomStore
import com.dangerfield.cards.server.domain.RoomSweepResult
import com.dangerfield.cards.server.domain.RoomVisibility
import com.dangerfield.cards.server.domain.SYSTEM_HOST_USER_ID
import com.dangerfield.cards.server.domain.UserId
import java.util.UUID
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
import kotlin.time.Instant

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
 * their socket dropped (or, for fresh joins, the join time itself).
 * The socket route schedules an in-process reaper per disconnect via
 * [reapIfStillDisconnected]; that's the live path. [sweepDisconnected]
 * remains as a bulk utility for tests + recovery scripts.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class InMemoryRoomService(
    private val clock: Clock,
    private val random: Random = Random.Default,
    // Durable backing (B2). Write-through on every mutation, hydrate on a
    // cache miss. Last + defaulted so the many direct test constructions stay
    // valid; DI resolves the real Postgres binding here (same pattern as
    // DefaultGameSessionRegistry's repos), the default only serves tests that
    // don't want Postgres in the loop.
    private val store: RoomStore = NoOpRoomStore,
    // Fired (outside the mutex) whenever a room is GC'd, so its game session +
    // escrow die with it. Defaulted to no-op for tests that don't care; DI binds
    // the real teardown coordinator.
    private val roomClosedListener: RoomClosedListener = RoomClosedListener.NoOp,
) : RoomService {

    private val log = org.slf4j.LoggerFactory.getLogger("RoomService")

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

    override suspend fun create(
        hostUserId: UserId,
        hostName: String,
        maxSeats: Int,
        hostAvatarEmoji: String,
        hostAvatarBackgroundColor: String?,
        buyIn: Long,
        visibility: RoomVisibility,
    ): CreateResult = mutex.withLock {
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
            avatarEmoji = sanitizeMemberAvatar(hostAvatarEmoji),
            avatarBackgroundColor = hostAvatarBackgroundColor,
        )
        val room = Room(
            code = code,
            hostUserId = hostUserId,
            createdAt = now,
            maxSeats = maxSeats,
            status = RoomStatus.Lobby,
            members = listOf(host),
            buyIn = buyIn,
            visibility = visibility,
        )
        rooms[code] = RoomState(room = room)
        persist(room)
        CreateResult.Success(room)
    }

    override suspend fun join(
        code: String,
        userId: UserId,
        name: String,
        avatarEmoji: String,
        avatarBackgroundColor: String?,
    ): JoinResult = mutex.withLock {
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
            avatarEmoji = sanitizeMemberAvatar(avatarEmoji),
            avatarBackgroundColor = avatarBackgroundColor,
        )
        val next = current.copy(members = (current.members + newMember).sortedBy { it.seatIndex })
        state.update(next)
        persist(next)
        JoinResult.Success(next)
    }

    override suspend fun findOrJoinPublic(
        userId: UserId,
        name: String,
        minBuyIn: Long,
        maxBuyIn: Long,
        blockedUserIds: Set<UserId>,
        avatarEmoji: String,
        avatarBackgroundColor: String?,
    ): MatchmakingResult = mutex.withLock {
        // Idempotent: already seated in an eligible room *in this range* → hand
        // it back, so a double-tap / reconnect doesn't mint a second table. A
        // search at a different tier is NOT idempotent against a stale seat at
        // another tier — it falls through to a real find/create for the range
        // asked for (the old seat self-heals via the forming-table reaper), so
        // the function never returns a room outside [minBuyIn, maxBuyIn].
        rooms.values.firstOrNull {
            it.room.isMatchmakingEligible &&
                it.room.memberFor(userId) != null &&
                it.room.buyIn in minBuyIn..maxBuyIn
        }?.let { return@withLock MatchmakingResult.Joined(it.room) }

        // Atomic pick: eligible, joinable, in-range, no blocked member; prefer the
        // most real humans (ties → oldest room, for stable convergence).
        val candidate = rooms.values
            .map { it.room }
            .filter { room ->
                room.isMatchmakingEligible &&
                    room.status == RoomStatus.Lobby &&
                    !room.isFull &&
                    room.buyIn in minBuyIn..maxBuyIn &&
                    room.members.none { it.userId in blockedUserIds }
            }
            .sortedWith(
                compareByDescending<Room> { room -> room.members.count { !it.isBot } }
                    .thenBy { it.createdAt },
            )
            .firstOrNull()

        val now = clock.now()
        if (candidate != null) {
            val state = rooms.getValue(candidate.code)
            val newMember = RoomMember(
                userId = userId,
                displayName = name,
                seatIndex = nextFreeSeat(candidate),
                joinedAt = now,
                isConnected = false,
                disconnectedAt = now,
                avatarEmoji = sanitizeMemberAvatar(avatarEmoji),
                avatarBackgroundColor = avatarBackgroundColor,
            )
            val next = candidate.copy(members = (candidate.members + newMember).sortedBy { it.seatIndex })
            state.update(next)
            persist(next)
            return@withLock MatchmakingResult.Joined(next)
        }

        // No eligible room — open a fresh Public table seated with just this
        // player. No bots: the disclosed fallback only fires later, on consent.
        // Synthetic system host (no per-host cap, no host-migration), buy-in
        // snapped to a canonical tier so overlapping ranges converge.
        val code = generateUniqueCode()
        val founder = RoomMember(
            userId = userId,
            displayName = name,
            seatIndex = 0,
            joinedAt = now,
            isConnected = false,
            disconnectedAt = now,
            avatarEmoji = sanitizeMemberAvatar(avatarEmoji),
            avatarBackgroundColor = avatarBackgroundColor,
        )
        val room = Room(
            code = code,
            hostUserId = SYSTEM_HOST_USER_ID,
            createdAt = now,
            maxSeats = RoomService.MAX_SEATS,
            status = RoomStatus.Lobby,
            members = listOf(founder),
            buyIn = BuyInTier.within(minBuyIn, maxBuyIn),
            visibility = RoomVisibility.Public,
        )
        rooms[code] = RoomState(room = room)
        persist(room)
        log.info("Matchmaking opened public room $code at buy-in ${room.buyIn} for $userId")
        MatchmakingResult.Created(room)
    }

    override suspend fun leave(code: String, userId: UserId): LeaveResult {
        var closedRoom: Room? = null
        val result = mutex.withLock {
            val state = rooms[code] ?: return@withLock LeaveResult.RoomNotFound
            val current = state.room
            if (current.memberFor(userId) == null) return@withLock LeaveResult.NotInRoom

            val remaining = current.members.filterNot { it.userId == userId }
            if (remaining.isEmpty() || remaining.all { it.isBot }) {
                // Last one out kills the lights — and a table left with only bots is
                // "empty" for our purposes: nobody's watching, host-migration would
                // otherwise promote a bot to host, and the bot driver idles it anyway.
                // GC it (memory + durable) so an abandoned fallback table doesn't
                // linger; the room-closed listener ends its session so nothing leaks.
                rooms.remove(code)
                forget(code)
                closedRoom = current
                // Info: room lifecycle end — explains "the room disappeared."
                log.info("Room $code closed: last member $userId left")
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
            persist(next)
            // Info: member churn + host migration is session-meaningful — answers
            // "who left / who's host now" when a game's flow is reported as broken.
            if (nextHostUserId != current.hostUserId) {
                log.info("Room $code: $userId left (was host); host migrated to $nextHostUserId, ${remaining.size} remain")
            } else {
                log.info("Room $code: $userId left, ${remaining.size} remain")
            }
            LeaveResult.Success(roomGone = false)
        }
        closedRoom?.let { roomClosedListener.onRoomClosed(it) }
        return result
    }

    override suspend fun addBot(
        code: String,
        requestedBy: UserId,
        difficulty: BotDifficulty,
        seatIndex: Int?,
        revealed: Boolean,
    ): AddBotResult = mutex.withLock {
        val state = rooms[code] ?: return@withLock AddBotResult.RoomNotFound
        val current = state.room
        if (current.hostUserId != requestedBy) return@withLock AddBotResult.NotHost
        if (current.status != RoomStatus.Lobby) return@withLock AddBotResult.NotJoinable(current.status)
        if (current.isFull) return@withLock AddBotResult.Full

        val seat = seatIndex ?: nextFreeSeat(current)
        if (seat !in 0 until current.maxSeats) return@withLock AddBotResult.SeatTaken
        if (current.members.any { it.seatIndex == seat }) return@withLock AddBotResult.SeatTaken

        val next = current.copy(
            members = (current.members + newBotMember(current, difficulty, revealed, seat)).sortedBy { it.seatIndex },
        )
        state.update(next)
        persist(next)
        AddBotResult.Success(next)
    }

    override suspend fun fillBotsUpTo(
        code: String,
        requestedBy: UserId,
        target: Int,
        difficulty: BotDifficulty,
        revealed: Boolean,
    ): AddBotResult = mutex.withLock {
        val state = rooms[code] ?: return@withLock AddBotResult.RoomNotFound
        var current = state.room
        if (current.hostUserId != requestedBy) return@withLock AddBotResult.NotHost
        if (current.status != RoomStatus.Lobby) return@withLock AddBotResult.NotJoinable(current.status)

        // Add bots up to [target] (or capacity) in ONE critical section, so a
        // concurrent double-tap can't each fill from the same starting size and
        // overshoot past the target into a packed table. Already at/over target →
        // no-op success (idempotent).
        while (current.members.size < target && !current.isFull) {
            val seat = nextFreeSeat(current)
            current = current.copy(
                members = (current.members + newBotMember(current, difficulty, revealed, seat)).sortedBy { it.seatIndex },
            )
        }
        state.update(current)
        persist(current)
        AddBotResult.Success(current)
    }

    private fun newBotMember(room: Room, difficulty: BotDifficulty, revealed: Boolean, seat: Int): RoomMember {
        val personality = pickBotPersonality(room, difficulty)
        return RoomMember(
            userId = UserId(UUID.randomUUID()),
            // Revealed bots wear their personality name (Jane/David/…), matching
            // the solo-game roster. A hidden bot would draw a human-style name
            // from a pool instead; not needed until matchmaking auto-fill ships.
            displayName = personality.name,
            seatIndex = seat,
            joinedAt = clock.now(),
            // Bots are always present: connected + no disconnect stamp means the
            // reaper (which requires !isConnected) can never free their seat.
            isConnected = true,
            disconnectedAt = null,
            // The reserved robot emoji is the wire signal for "revealed bot".
            // A hidden bot would carry a normal avatar; left blank here since
            // this slice only produces revealed bots.
            avatarEmoji = if (revealed) RESERVED_BOT_AVATAR_EMOJI else "",
            avatarBackgroundColor = null,
            bot = BotSeat(personality = personality, difficulty = difficulty, revealed = revealed),
        )
    }

    override suspend fun removeBot(
        code: String,
        requestedBy: UserId,
        botUserId: UserId,
    ): RemoveBotResult = mutex.withLock {
        val state = rooms[code] ?: return@withLock RemoveBotResult.RoomNotFound
        val current = state.room
        if (current.hostUserId != requestedBy) return@withLock RemoveBotResult.NotHost
        val target = current.members.firstOrNull { it.userId == botUserId }
        if (target?.bot == null) return@withLock RemoveBotResult.NotABot
        val next = current.copy(members = current.members.filterNot { it.userId == botUserId })
        state.update(next)
        persist(next)
        RemoveBotResult.Success(next)
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
        persist(next)
        next
    }

    override suspend fun markPlaying(code: String): Room? = mutex.withLock {
        val state = rooms[code] ?: return@withLock null
        val current = state.room
        if (current.status == RoomStatus.Playing) return@withLock current
        val next = current.copy(status = RoomStatus.Playing)
        state.update(next)
        persist(next)
        next
    }

    override suspend fun markFinished(code: String): Room? = mutex.withLock {
        val state = rooms[code] ?: return@withLock null
        val current = state.room
        if (current.status == RoomStatus.Lobby) return@withLock current
        val next = current.copy(status = RoomStatus.Lobby)
        state.update(next)
        persist(next)
        next
    }

    override suspend fun find(code: String): Room? = mutex.withLock {
        (rooms[code] ?: hydrateLocked(code))?.room
    }

    override suspend fun observe(code: String): Flow<Room>? = mutex.withLock {
        (rooms[code] ?: hydrateLocked(code))?.flow?.asStateFlow()
    }

    override suspend fun snapshot(): List<Room> = mutex.withLock {
        // Stable order by creation time keeps the admin dashboard scan
        // predictable; ties (same instant from FixedClock) fall back to
        // code so the output is fully deterministic.
        rooms.values.map { it.room }
            .sortedWith(compareBy({ it.createdAt }, { it.code }))
    }

    override suspend fun sweepDisconnected(maxIdle: Duration): RoomSweepResult {
        val closedRooms = mutableListOf<Room>()
        val result = mutex.withLock {
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
                if (survivors.isEmpty() || survivors.all { it.isBot }) {
                    // Sweep emptied the room — or left only bots, which we treat the
                    // same (see leave()). GC it + close its session after the lock.
                    rooms.remove(code)
                    forget(code)
                    closedRooms += current
                    roomsReaped++
                    // No flow update — the room is gone and any straggling
                    // subscribers will simply stop receiving emissions.
                } else {
                    val swept = current.copy(members = survivors)
                    state.update(swept)
                    persist(swept)
                }
            }
            // Durable backstop: reclaim persisted rooms with no in-memory owner.
            // The per-disconnect reaper + the loop above only ever touch rooms
            // loaded in `rooms`; a process death leaves persisted rooms behind with
            // no owner and nothing else deletes them. Anything still live (in
            // `rooms`) is excluded so a long-running game is never swept. Best-effort
            // — a DB hiccup here must not fail the in-memory sweep that already ran.
            val orphansReaped = Catching { store.deleteStaleRooms(cutoff, keepCodes = rooms.keys.toSet()) }
                .onFailure { log.warn("Durable stale-room sweep failed", it) }
                .getOrNull() ?: 0

            RoomSweepResult(
                membersReaped = membersReaped,
                roomsReaped = roomsReaped,
                roomsSeen = roomsSeen,
                orphanedRoomsReaped = orphansReaped,
            )
        }
        closedRooms.forEach { roomClosedListener.onRoomClosed(it) }
        return result
    }

    override suspend fun reapIfStillDisconnected(
        code: String,
        userId: UserId,
        expectedDisconnectedAt: Instant,
    ): Boolean {
        var closedRoom: Room? = null
        val reaped = mutex.withLock {
            val state = rooms[code] ?: return@withLock false
            val current = state.room
            val member = current.memberFor(userId) ?: return@withLock false
            // Only reap when the disconnect we're scheduled for is the one
            // still in place. A reconnect (disconnectedAt == null) or a
            // re-disconnect (different stamp) means a fresh timer was
            // scheduled and this call must no-op.
            if (member.isConnected || member.disconnectedAt != expectedDisconnectedAt) {
                return@withLock false
            }
            val survivors = current.members - member
            if (survivors.isEmpty() || survivors.all { it.isBot }) {
                rooms.remove(code)
                forget(code)
                closedRoom = current
                log.info("Room $code: reaped $userId after disconnect grace; only bots/none left, closed")
            } else {
                val swept = current.copy(members = survivors)
                state.update(swept)
                persist(swept)
                // Info: seat-freeing after a drop — explains "my opponent vanished"
                // or "I got kicked" in a reported session.
                log.info("Room $code: reaped $userId after disconnect grace, ${survivors.size} remain")
            }
            true
        }
        closedRoom?.let { roomClosedListener.onRoomClosed(it) }
        return reaped
    }

    /**
     * Write-through the full room snapshot. Best-effort (mirrors the B0 session
     * persist): a DB hiccup must never break a live lobby, so a failure is
     * logged and the in-memory state — which stays authoritative while the room
     * is loaded — carries on. Durability is a restart backstop, not a
     * gate on the response.
     */
    private suspend fun persist(room: Room) {
        Catching { store.save(room) }
            .onFailure { log.warn("Room ${room.code} persist failed", it) }
    }

    /** Write-through a room deletion. Best-effort, same rationale as [persist]. */
    private suspend fun forget(code: String) {
        Catching { store.delete(code) }
            .onFailure { log.warn("Room $code delete-persist failed", it) }
    }

    /**
     * Pull a room the in-memory map has no entry for from the durable store and
     * install it as a fresh [RoomState] (so observers attach to a live flow).
     * This is the server-restart recovery path: the first [find] / [observe] for
     * a persisted code rehydrates it. Members come back disconnected — sockets
     * re-establish presence, and the disconnect-grace sweep (its `disconnectedAt`
     * stamps survived) reaps any that never return. Caller must hold [mutex].
     */
    private suspend fun hydrateLocked(code: String): RoomState? {
        rooms[code]?.let { return it }
        val loaded = Catching { store.load(code) }
            .onFailure { log.warn("Room $code hydrate failed", it) }
            .getOrNull() ?: return null
        val state = RoomState(room = loaded)
        rooms[code] = state
        log.info("Room $code hydrated from durable store after a cache miss")
        return state
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

    /**
     * The robot emoji is reserved as the bot avatar — opponents read it as
     * "this seat is a backend bot." A human must never broadcast it (an old
     * or third-party client could put it on a profile), so we strip it to
     * blank here at the one chokepoint every member flows through. Blank
     * renders as initials downstream — the same fallback as a member with no
     * avatar — so the human appears as a normal player, never as a bot.
     * Mirrors `BotAvatarEmoji` in `:libraries:cards` (the server doesn't
     * depend on that client module; this is the deliberate cross-boundary
     * duplicate, like the avatar starter-pack fallback list).
     */
    private fun sanitizeMemberAvatar(emoji: String): String =
        if (emoji == RESERVED_BOT_AVATAR_EMOJI) "" else emoji

    /**
     * Pick a personality for a new bot that, where possible, differs from the
     * bots already seated so a multi-bot table spans archetypes instead of
     * cloning one name. [BotPersonality.forDifficulty] already orders its roster
     * to span archetypes; we ask for one more than the seated-bot count and take
     * the first not already in use, falling back to the last (a duplicate, which
     * is unavoidable once the roster of 5 is exhausted).
     */
    private fun pickBotPersonality(room: Room, difficulty: BotDifficulty): BotPersonality {
        val seatedBotNames = room.members.mapNotNull { it.bot?.personality?.name }.toSet()
        val candidates = BotPersonality.forDifficulty(difficulty, seatedBotNames.size + 1)
        return candidates.firstOrNull { it.name !in seatedBotNames } ?: candidates.last()
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

        /** The bot avatar emoji a human member may never carry. */
        const val RESERVED_BOT_AVATAR_EMOJI = "🤖"
    }
}
