package com.dangerfield.cards.server.domain

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Server-side multiplayer room. Each room gets a 6-char code and is
 * garbage-collected when the last member leaves. The in-memory registry
 * ([InMemoryRoomService]) is the live authority, backed by Postgres as a
 * write-through store ([RoomStore] → [PostgresRoomStore], `rooms` +
 * `room_members`, migration V65): every mutation persists the full room
 * snapshot, and a cache miss hydrates from the durable row — so codes and
 * membership survive a server restart. Rooms a process death strands in
 * the store are reaped by [RoomService.sweepDisconnected].
 *
 * Per the locked 2026-05-13 client/server-boundary decision, room state
 * is server-authoritative: clients observe via the per-room WebSocket
 * channel and never assume they know the truth.
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
    /**
     * Host-chosen buy-in = each player's starting stack. Blinds derive from it
     * via [RoomSettings.forBuyIn]. Used at hand-start instead of the engine
     * default so the table actually plays at the chosen stakes.
     */
    val buyIn: Long = RoomSettings.DEFAULT_BUY_IN,
    /**
     * Who can discover + join this room. [RoomVisibility.Private] (the default,
     * matching every pre-matchmaking room) is code-only. [RoomVisibility.Open]
     * is a human-hosted room the creator opted into matchmaking discovery for.
     * [RoomVisibility.Public] is a matchmaker-created table with a synthetic
     * system host, dealt by the server. Only Open + Public are matchmaking-
     * eligible. Defaulted so existing call sites + persisted pre-V66 rows read
     * as Private.
     */
    val visibility: RoomVisibility = RoomVisibility.Private,
    /**
     * Host-chosen table cosmetics: the felt + card-back catalog product ids the
     * creator had equipped at create time, applied table-wide so every player
     * sees the host's look (SHOP-3). Opaque to the server — it stores + echoes
     * the ids and never interprets them; the client maps them to a felt/card-back
     * style. Null when the host had nothing equipped in that slot, in which case
     * the client falls back to each player's own equipped cosmetic.
     */
    val feltProductId: String? = null,
    val cardBackProductId: String? = null,
) {
    /** Full engine settings derived from [buyIn] + [maxSeats]. */
    val settings: RoomSettings get() = RoomSettings.forBuyIn(buyIn, maxSeats)

    /** Matchmaking can seat a stranger here (Open opt-in, or a Public table). */
    val isMatchmakingEligible: Boolean
        get() = visibility == RoomVisibility.Open || visibility == RoomVisibility.Public

    val seatCount: Int get() = members.size
    val isFull: Boolean get() = seatCount >= maxSeats
    fun memberFor(userId: UserId): RoomMember? = members.firstOrNull { it.userId == userId }

    /**
     * The member who currently wields host powers (add/remove bots, start a
     * private hand). Mirrors the client's `effectiveHostUserId` so both sides
     * always agree on who sees + may use the host affordances: the first
     * *connected* human in seat order, falling back to the first human when
     * nobody's connected yet (a just-created room whose socket hasn't opened).
     *
     * The tagged [hostUserId] is deliberately not consulted. It can point at
     * the synthetic system host (matchmaker-created Public rooms) or at a
     * disconnected creator — both left the room's real occupants unable to do
     * anything the client showed them buttons for (ROOM-16: the sole human of
     * a rejoined matchmaking room got `not_host` on add-bots seven times).
     */
    val effectiveHostUserId: UserId?
        get() {
            val humans = members.filter { !it.isBot }
            return (humans.firstOrNull { it.isConnected } ?: humans.firstOrNull())?.userId
        }

    /**
     * Authority check for host-gated mutations. The synthetic system host
     * always passes — server-side callers (the disclosed-bot fallback) act as
     * it, and no real JWT can carry its all-zeros subject.
     */
    fun wieldsHostPowers(userId: UserId): Boolean =
        userId == SYSTEM_HOST_USER_ID || userId == effectiveHostUserId
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
    /**
     * Non-null only for backend bot seats added to the room. Drives the server
     * bot driver and — when [BotSeat.revealed] — the wire-level bot reveal. Null
     * for every human member. Bots never disconnect (created connected, never
     * reaped), so this is the sole source of truth for "is this seat a bot".
     */
    val bot: BotSeat? = null,
) {
    /** True for a backend bot seat. Prefer this over inspecting [userId]. */
    val isBot: Boolean get() = bot != null
}

/**
 * [Lobby] = pre-deal or between hands (host can start, new members can
 * sit). [Playing] = a hand is in flight; new joins are still accepted —
 * the joiner becomes a member, takes a seat slot, and is dealt in at
 * the next hand boundary (the in-flight hand's seat order is fixed, so
 * they spectate it). [Finished] is a terminal state that rejects new
 * members; not currently set anywhere (sketched in for future).
 */
enum class RoomStatus { Lobby, Playing, Finished }

/**
 * Discovery + join policy for a room.
 *
 *  - [Private] — joinable only by code. Every room before matchmaking is this.
 *  - [Open] — human-hosted but discoverable by the public matchmaker; the
 *    creator flipped "let anyone join". Server-dealt like [Public] (auto-deals
 *    once 2+ players are present) — "open to anyone" means "fill it and play",
 *    so a matched stranger never waits on a host tapping Start. No surprise bots
 *    unless the host opts into bot-fill.
 *  - [Public] — created by the matchmaker with a synthetic system host and
 *    dealt by the server. Eligible for the disclosed bot fallback.
 */
enum class RoomVisibility { Private, Open, Public }

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
        buyIn: Long = RoomSettings.DEFAULT_BUY_IN,
        // Private by default. The create-game "open to anyone" toggle passes
        // [RoomVisibility.Open] so the matchmaker can seat strangers here.
        visibility: RoomVisibility = RoomVisibility.Private,
        // Host's equipped felt + card-back product ids at create time, applied
        // table-wide (SHOP-3). Null = the host had nothing equipped in that slot.
        feltProductId: String? = null,
        cardBackProductId: String? = null,
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
     * Public matchmaking: seat [userId] into the best eligible Open/Public room
     * whose buy-in is in `[minBuyIn, maxBuyIn]`, else open a fresh Public table
     * for them (no bots — the disclosed bot fallback only fires later, on
     * consent). "Best" = the most real humans already seated, so searchers
     * cluster onto live tables instead of scattering.
     *
     * Runs as a single critical section so the pick-and-seat is atomic — two
     * racing searchers can't both claim the last seat, and a room can't be
     * GC'd / torn down between the scan and the join (the loser falls through to
     * create). [blockedUserIds] (both block directions) must be computed by the
     * caller *before* this call — never query the friend graph while the room
     * mutex is held; rooms containing a blocked member are skipped. Idempotent:
     * a searcher already seated in an eligible room gets that room back.
     */
    suspend fun findOrJoinPublic(
        userId: UserId,
        name: String,
        minBuyIn: Long,
        maxBuyIn: Long,
        blockedUserIds: Set<UserId>,
        avatarEmoji: String = "",
        avatarBackgroundColor: String? = null,
    ): MatchmakingResult

    /**
     * Read-only matchmaking discovery: the eligible Open/Public tables whose
     * buy-in is in `[minBuyIn, maxBuyIn]` that [userId] could join, ordered the
     * same way [findOrJoinPublic] would pick (most real humans first, ties to the
     * oldest room). Unlike [findOrJoinPublic] this seats no one and creates
     * nothing — it powers the matchmaking *chooser*, where the user picks which
     * qualifying table to join rather than being silently auto-seated into the
     * first match.
     *
     * Same eligibility predicate as the auto-pick (eligible, not Finished, not
     * full, in range, no blocked member). A table [userId] is already seated in
     * is included (the chooser can show "you're already here"). [blockedUserIds]
     * (both directions) is computed by the caller before the call, like
     * [findOrJoinPublic] — never query the friend graph under the rooms mutex.
     */
    suspend fun findPublicCandidates(
        userId: UserId,
        minBuyIn: Long,
        maxBuyIn: Long,
        blockedUserIds: Set<UserId>,
    ): List<Room>

    /**
     * Explicit leave. Frees the seat. When the room empties, the
     * service garbage-collects it (returns [LeaveResult.Success] and
     * the next [find] returns null).
     */
    suspend fun leave(code: String, userId: UserId): LeaveResult

    /**
     * Seat a backend-driven bot. Host-only — gated on [Room.wieldsHostPowers],
     * so the effective host (first connected human; matches what the client
     * shows buttons to) may add bots even on a system-hosted matchmaking room.
     * The bot gets a synthetic [UserId], a personality auto-assigned to
     * span archetypes against the bots already at the table, and is marked
     * connected immediately (bots never disconnect, so they're never swept).
     *
     * [seatIndex] null fills the next free seat. [revealed] = true (the lobby
     * default) advertises the bot on the wire; false produces a stealth bot
     * that presents as an ordinary member — reserved for future matchmaking
     * auto-fill, not used by the lobby.
     */
    suspend fun addBot(
        code: String,
        requestedBy: UserId,
        difficulty: BotDifficulty,
        seatIndex: Int? = null,
        revealed: Boolean = true,
    ): AddBotResult

    /**
     * Atomically seat backend bots until the room holds [target] members (or hits
     * capacity), all in one critical section. The disclosed-bot fallback uses this
     * instead of looping [addBot] so two racing `play-bots` taps can't each fill
     * from the same starting count and overshoot into a packed table. Host-only
     * (the public table's system host), Lobby-only. Idempotent: already at/over
     * [target] is a no-op success.
     */
    suspend fun fillBotsUpTo(
        code: String,
        requestedBy: UserId,
        target: Int,
        difficulty: BotDifficulty,
        revealed: Boolean = true,
    ): AddBotResult

    /**
     * Remove a previously-added bot. Host-only ([Room.wieldsHostPowers]). The
     * effective host is always a human, so this never empties the room; no
     * host migration / GC concerns apply.
     */
    suspend fun removeBot(code: String, requestedBy: UserId, botUserId: UserId): RemoveBotResult

    /**
     * "Bots step aside for humans." On a **public** table that now holds **two
     * or more humans plus at least one bot**, drop **one** bot from the room so
     * the table converges toward an all-human game one hand at a time. No host
     * gate — the matchmaker's system host owns public tables, and this is server
     * policy, not a user action.
     *
     * Called at each hand boundary by the socket route's trim collector.
     * [handNumber] makes it **idempotent per hand**: the collector fires once
     * per remaining subscriber, but only the first call for a given
     * (code, handNumber) trims — the rest are no-ops. Returns the trimmed bot's
     * [UserId] (so the caller can drop it from the live game session too), or
     * null when nothing was trimmed (not public, <2 humans, no bots, the cushion
     * is being held, or this hand was already trimmed).
     *
     * One-per-hand is deliberate: a table that found a second human shouldn't
     * dump all its bots at once mid-session — it eases the humans in while the
     * pot they're already contesting plays out, and keeps the seat count from
     * lurching. **Gentle landing:** a freshly-rescued *pair* isn't stripped to a
     * bare heads-up — a small disclosed-bot cushion is held until either a third
     * human arrives or the cushion bot busts out, so the table never snaps to an
     * empty-feeling two-person duel on the way to all-human. The lone-human case
     * is untouched (that's still a real-money practice table); only a
     * genuinely-rescued table sheds bots.
     */
    suspend fun trimBotForNewHumans(code: String, handNumber: Int): UserId?

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
     * Also reclaims persisted rooms with no in-memory owner — the rooms a
     * process death strands in the durable store, which the per-member reaper
     * (an in-process timer that dies with the process) can never reach. The
     * count comes back as [RoomSweepResult.orphanedRoomsReaped].
     *
     * Idempotent. The happy-path seat reclaim is the per-disconnect reaper
     * ([reapIfStillDisconnected], scheduled on each socket close); this sweep is
     * the cron-driven backstop ([RoomService] mounts it on `POST
     * /v1/admin/sweep-rooms`) for seats + rooms that outlive the process that
     * scheduled their reaper.
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
    /**
     * Persisted rooms with no in-memory owner that were deleted from the durable
     * store (the abandoned-after-restart leak). Zero for an in-memory-only
     * service (the [NoOpRoomStore] default).
     */
    val orphanedRoomsReaped: Int = 0,
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

sealed interface AddBotResult {
    data class Success(val room: Room) : AddBotResult
    data object RoomNotFound : AddBotResult
    /** Caller isn't the room host — only the host may add bots. */
    data object NotHost : AddBotResult
    data object Full : AddBotResult
    /** Room exists but its current [RoomStatus] doesn't accept new seats. */
    data class NotJoinable(val status: RoomStatus) : AddBotResult
    /** The requested seat index is out of range or already occupied. */
    data object SeatTaken : AddBotResult
}

sealed interface RemoveBotResult {
    data class Success(val room: Room) : RemoveBotResult
    data object RoomNotFound : RemoveBotResult
    data object NotHost : RemoveBotResult
    /** No bot with that id is seated (unknown id, or it's a human member). */
    data object NotABot : RemoveBotResult
}
