package com.dangerfield.cards.server.domain

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Server-side multiplayer room. V1 rooms are ephemeral: they live in
 * memory only, get a 6-char code, and are garbage-collected when the
 * last member leaves. There's no Postgres backing yet — when MP graduates
 * past "lobby + presence" into actual gameplay state with reconnect-
 * after-cold-start, we'll need persistence.
 *
 * Per the locked 2026-05-13 client/server-boundary decision, room state
 * is server-authoritative: clients observe via the per-room WebSocket
 * channel and never assume they know the truth.
 *
 * Why not persist now: in-memory is simpler and matches the user model
 * for V1 ("join a quick game with friends, when everyone leaves the
 * room is gone"). Persistence trades simplicity for cold-start
 * survival, which we don't need until V1.x adds longer-form play.
 */
@OptIn(ExperimentalTime::class)
data class Room(
    /** Unguessable 6-char alphanumeric code (no 0/O/1/I/L ambiguity). */
    val code: String,
    /** The user who first created the room. Stays host even on reconnect. */
    val hostUserId: UserId,
    val createdAt: Instant,
    val maxSeats: Int,
    val status: RoomStatus,
    /** Members in seat-index order. List is the authoritative seating chart. */
    val members: List<RoomMember>,
) {
    val seatCount: Int get() = members.size
    val isFull: Boolean get() = seatCount >= maxSeats
    fun memberFor(userId: UserId): RoomMember? = members.firstOrNull { it.userId == userId }
}

@OptIn(ExperimentalTime::class)
data class RoomMember(
    val userId: UserId,
    /** Snapshot at the moment the user joined — keeps the UI stable even
     *  if the user later edits their profile name from another device. */
    val displayName: String,
    /** 0-based seat — stable across reconnect. Used by gameplay later. */
    val seatIndex: Int,
    val joinedAt: Instant,
    /**
     * Live WebSocket presence. False when their socket has dropped but
     * the seat is being held for a reconnect grace window. The seat
     * doesn't free up until [LeaveResult.Success] or [RoomService.sweepDisconnected]
     * decides the grace period has elapsed.
     */
    val isConnected: Boolean,
    /**
     * Wall-clock instant the socket dropped. Null while the member is
     * connected; set on every disconnect, cleared on every reconnect.
     * Used by [RoomService.sweepDisconnected] to decide which seats are
     * stale enough to free. Never surfaced to the wire — internal book-
     * keeping only (the UI shows isConnected; the seat-grace countdown
     * is server policy, not client-visible state).
     */
    val disconnectedAt: Instant? = null,
)

/**
 * V1 only uses [Lobby]. [Playing] / [Finished] are sketched in for the
 * Phase 4.2 game-state pass; the room machine refuses joins outside
 * Lobby today.
 */
enum class RoomStatus { Lobby, Playing, Finished }

/**
 * High-level room operations the HTTP routes + WebSocket session
 * handlers talk to. Implementations are concurrent-safe — multiple
 * connections per room hit these methods simultaneously.
 */
interface RoomService {

    /**
     * Create a fresh room. Picks a unique code, places the host in
     * seat 0 (connected=false until they open their socket), returns
     * the populated [Room].
     */
    suspend fun create(hostUserId: UserId, hostName: String, maxSeats: Int = MAX_SEATS): Room

    /**
     * Idempotent join. If the user is already a member, returns
     * [JoinResult.AlreadyJoined] with the existing seat preserved.
     * Otherwise drops them in the next free seat.
     */
    suspend fun join(code: String, userId: UserId, name: String): JoinResult

    /**
     * Explicit leave. Frees the seat. When the room empties, the
     * service garbage-collects it (returns [LeaveResult.Success] and
     * the next [find] returns null).
     */
    suspend fun leave(code: String, userId: UserId): LeaveResult

    /** Mark a member's socket connected / disconnected. No-op if room or
     *  member missing — callers use this from WS open / close handlers. */
    suspend fun markConnected(code: String, userId: UserId, connected: Boolean): Room?

    /** One-shot read. Null when the room has been GC'd. */
    suspend fun find(code: String): Room?

    /**
     * Live updates. The flow emits the current [Room] on subscribe + on
     * every mutation (join/leave/connect). Returns null when the room
     * doesn't exist — callers handle "you tried to subscribe to a GC'd
     * room" cleanly.
     */
    suspend fun observe(code: String): Flow<Room>?

    /**
     * Reaps members whose socket has been dropped for at least [maxIdle]
     * across every live room. Same effect on subscribers as an explicit
     * `/leave` — observers see a [Room] snapshot minus the swept members
     * and (per [RoomSocketRoutes]) a `member_left` delta for each one.
     *
     * When a sweep empties a room, the room itself is GC'd just like
     * [leave]'s last-out branch. The room codes never resurrect — a
     * future join attempt against a swept code returns [JoinResult.RoomNotFound].
     *
     * Idempotent. Safe to call from a cron at any cadence the operator
     * wants — V1 ships it as a token-gated admin endpoint to match the
     * orphan-anon sweep pattern.
     */
    suspend fun sweepDisconnected(maxIdle: Duration): RoomSweepResult

    companion object {
        const val MAX_SEATS = 6
    }
}

/**
 * Summary of a single [RoomService.sweepDisconnected] pass. Reported back
 * to the admin endpoint so the cron caller can log / alert on the numbers.
 */
data class RoomSweepResult(
    /** Number of members whose seats were freed across all rooms. */
    val membersReaped: Int,
    /** Number of rooms emptied as a side effect (so also GC'd). */
    val roomsReaped: Int,
    /** Total live rooms at the start of the sweep. Useful for sanity-checking. */
    val roomsSeen: Int,
)

sealed interface JoinResult {
    data class Success(val room: Room) : JoinResult
    /** The user was already in the room — same seat preserved, idempotent. */
    data class AlreadyJoined(val room: Room) : JoinResult
    data object RoomNotFound : JoinResult
    data object Full : JoinResult
    /** Room exists but its current [RoomStatus] doesn't accept joins. */
    data class NotJoinable(val status: RoomStatus) : JoinResult
}

sealed interface LeaveResult {
    /** Member removed. [Success.roomGone] = true when this was the last
     *  member and the room itself was reaped — callers don't need to
     *  broadcast further. */
    data class Success(val roomGone: Boolean) : LeaveResult
    data object RoomNotFound : LeaveResult
    data object NotInRoom : LeaveResult
}
