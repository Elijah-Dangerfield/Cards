package com.dangerfield.cards.libraries.rooms

/**
 * Client-side mirror of the server's [Room]. Pure domain — no
 * serialization annotations, no Compose types. Features render directly
 * from this; the DTO/wire shape lives in
 * `:libraries:rooms:impl`.
 *
 * `members` is in seat-index order; the V1 lobby renders seats top-to-
 * bottom in this order. Gameplay (Phase 4.2) will probably continue to
 * read it the same way.
 */
data class Room(
    val code: String,
    val hostUserId: String,
    val createdAtEpochMs: Long,
    val maxSeats: Int,
    val status: RoomStatus,
    val members: List<RoomMember>,
    /** Host-chosen buy-in (= starting stack) and the blinds derived from it,
     *  for display in the lobby. 0 until the first room snapshot. */
    val buyIn: Long = 0,
    val smallBlind: Long = 0,
    val bigBlind: Long = 0,
    /**
     * Who can discover the table + how it deals. [RoomVisibility.Open] and
     * [RoomVisibility.Public] are server-dealt (no host Start — the lobby
     * auto-follows into the game); [RoomVisibility.Private] is host-dealt.
     * Defaulted so pre-visibility snapshots read as Private.
     */
    val visibility: RoomVisibility = RoomVisibility.Private,
) {
    val seatCount: Int get() = members.size
    val isFull: Boolean get() = seatCount >= maxSeats
    fun isHost(userId: String): Boolean = userId == hostUserId
    fun memberFor(userId: String): RoomMember? = members.firstOrNull { it.userId == userId }

    /** Server-dealt (no host Start): Open + Public. Private waits for the host. */
    val isServerDealt: Boolean get() = visibility == RoomVisibility.Open || visibility == RoomVisibility.Public
}

data class RoomMember(
    val userId: String,
    val displayName: String,
    val seatIndex: Int,
    val joinedAtEpochMs: Long,
    /** Live WebSocket presence per the server. False = seat held but
     *  socket dropped (reconnect grace). */
    val isConnected: Boolean,
    /** True for a *revealed* backend bot (the server gates this behind its
     *  stealth flag — a hidden bot arrives here as `false`, indistinguishable
     *  from a human). Drives the BOT badge + robot avatar in the seat grid. */
    val isBot: Boolean = false,
    /** Avatar emoji + background-color hex, snapshotted server-side. Present for
     *  every member now (including the reserved 🤖 for a revealed bot); null/blank
     *  falls back to a name initial. */
    val avatarEmoji: String? = null,
    val avatarBackgroundColorHex: String? = null,
)

enum class RoomStatus { Lobby, Playing, Finished, Unknown }

enum class RoomVisibility { Private, Open, Public, Unknown }
