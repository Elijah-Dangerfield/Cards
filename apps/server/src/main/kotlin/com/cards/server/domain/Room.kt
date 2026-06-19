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
    /**
     * Avatar emoji + background-color hex, snapshotted at join alongside
     * [displayName] (same stability rationale). Carried onto the engine seat
     * at hand-start so opponents render the real avatar. Defaulted so older
     * call sites / tests compile; the live join path always supplies them
     * from the member's profile.
     */
    val avatarEmoji: String = "",
    val avatarBackgroundColor: String? = null,
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
     * the populated [Room] inside [CreateResult.Success] — or refuses
     * with [CreateResult.TooManyRooms] when the caller already hosts
     * [MAX_ROOMS_PER_HOST] live rooms.
     *
     * The per-host cap is a soft abuse stop: a user who creates rooms
     * faster than they leave them is either a bot or a buggy client.
     * The cap is liberal enough that no honest workflow hits it
     * (re-create after a crash is fine — the old rooms get GC'd by
     * the sweep or by the last-out leave).
     */
    suspend fun create(
        hostUserId: UserId,
        hostName: String,
        maxSeats: Int = MAX_SEATS,
        hostAvatarEmoji: String = "",
        hostAvatarBackgroundColor: String? = null,
    ): CreateResult

    /**
     * Idempotent join. If the user is already a member, returns
     * [JoinResult.AlreadyJoined] with the existing seat preserved.
     * Otherwise drops them in the next free seat.
     */
    suspend fun join(
        code: String,
        userId: UserId,
        name: String,
        avatarEmoji: String = "",
        avatarBackgroundColor: String? = null,
    ): JoinResult

    /**
     * Explicit leave. Frees the seat. When the room empties, the
     * service garbage-collects it (returns [LeaveResult.Success] and
     * the next [find] returns null).
     */
    suspend fun leave(code: String, userId: UserId): LeaveResult

    /** Mark a member's socket connected / disconnected. No-op if room or
     *  member missing — callers use this from WS open / close handlers. */
    suspend fun markConnected(code: String, userId: UserId, connected: Boolean): Room?

    /**
     * Transition the room from [RoomStatus.Lobby] to [RoomStatus.Playing].
     * No-op (returns current room) if already Playing; rejected (returns
     * null) if the room is missing. Triggered by the socket route after a
     * successful [RoomClientFrame.StartHand] dispatch into the game-session
     * registry. The status flip cascades through the existing room flow
     * so guests' lobby screens see "we're playing now" via the next
     * Snapshot.
     */
    suspend fun markPlaying(code: String): Room?

    /**
     * Transition the room from [RoomStatus.Playing] back to [RoomStatus.Lobby].
     * Phase 2a doesn't call this — the hand ends but the room stays
     * Playing so the post-hand summary sticks. Reserved for the polish
     * phase where "back to lobby" is a user choice; the implementation is
     * here so the symmetry with [markPlaying] is obvious in the interface.
     */
    suspend fun markFinished(code: String): Room?

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
     * Idempotent. Kept as a test utility; in production each disconnect
     * schedules its own per-member reaper via [reapIfStillDisconnected],
     * so the live system doesn't depend on a periodic sweep.
     */
    suspend fun sweepDisconnected(maxIdle: Duration): RoomSweepResult

    /**
     * Per-member reaper used by the socket route's in-process grace
     * timer. Removes the member only when they're still disconnected
     * AND their `disconnectedAt` stamp matches [expectedDisconnectedAt]
     * — meaning no reconnect/redrop happened during the grace window.
     *
     * Returns true when the member was actually reaped. A reconnect
     * followed by a fresh disconnect schedules a new reaper with the
     * updated stamp, so the original call short-circuits to no-op.
     *
     * When this empties the room, the room itself is GC'd just like
     * [leave]'s last-out branch and [sweepDisconnected].
     */
    suspend fun reapIfStillDisconnected(code: String, userId: UserId, expectedDisconnectedAt: Instant): Boolean

    /**
     * Lightweight summary of every live room, ordered for stable display.
     * Powers the token-gated `GET /v1/admin/rooms` endpoint — used by ops
     * to verify the sweep is doing its job, spot abandoned rooms before
     * the next sweep tick, and answer "how many people are in MP right
     * now." Never exposed to authenticated clients; the room socket is
     * the only public room-discovery surface.
     */
    suspend fun snapshot(): List<Room>

    companion object {
        const val MAX_SEATS = 6

        /**
         * Soft cap on concurrent rooms a single user can host. Set high
         * enough that no honest workflow hits it (re-create after a
         * crash, leave + create, etc.) but low enough that a malicious
         * client can't hoard codes and exhaust the in-memory map.
         */
        const val MAX_ROOMS_PER_HOST = 3
    }
}

sealed interface CreateResult {
    data class Success(val room: Room) : CreateResult
    /** The host already hosts [RoomService.MAX_ROOMS_PER_HOST] live rooms;
     *  leaving one frees the slot. */
    data class TooManyRooms(val activeCount: Int) : CreateResult
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
