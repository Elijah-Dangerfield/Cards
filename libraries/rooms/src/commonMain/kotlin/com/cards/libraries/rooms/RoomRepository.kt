package com.dangerfield.cards.libraries.rooms

import kotlinx.coroutines.flow.Flow

/**
 * Single client surface for the multiplayer room lifecycle.
 * Wraps the HTTP routes (create / join / leave) and the per-room
 * WebSocket so feature code never has to think about the split.
 *
 *  - [createRoom] / [joinRoom] / [leaveRoom] are one-shot HTTP calls
 *    that return a sealed outcome.
 *  - [connect] opens (and reconnects) a WebSocket and returns a
 *    [RoomConnectionHandle] that splits the stream into a lobby-shaped
 *    `connection` flow, a gameplay `gameplayFrames` flow, and an
 *    outbound `send` channel. Transport failures surface as
 *    [RoomConnection.Reconnecting] on the connection flow; neither
 *    flow ever throws.
 *
 * Lifecycle: handles for the same room code share one underlying WS.
 * The socket opens when either flow is first collected and closes
 * (after a small linger) when no flow has any active collector — so
 * the lobby + gameplay screen can each hold their own handle without
 * spinning up duplicate connections, and navigating between them
 * never tears the socket down.
 *
 * Membership: [connect] assumes the user is already a member of the
 * room. Call [joinRoom] first; the LobbyScreen does this in sequence
 * (join → connect).
 */
interface RoomRepository {

    suspend fun createRoom(maxSeats: Int? = null, buyIn: Long? = null): CreateRoomOutcome

    suspend fun joinRoom(code: String): JoinRoomOutcome

    suspend fun leaveRoom(code: String): LeaveRoomOutcome

    /**
     * Host-only: seat a backend-driven bot in the room. The server assigns the
     * bot's personality and drives it; the new seat arrives over the live socket
     * like any other member. [seatIndex] null fills the next free seat.
     */
    suspend fun addBot(code: String, seatIndex: Int? = null): AddBotOutcome

    /** Host-only: remove a bot previously added with [addBot]. */
    suspend fun removeBot(code: String, botUserId: String): RemoveBotOutcome

    /**
     * Rooms the signed-in user is currently a member of. Called on cold
     * launch by the home/launch flow so we can offer "rejoin / forfeit"
     * before silently stranding a player whose previous session left a
     * seat warm via the `disconnectedAt` grace timer.
     *
     * A successful response replays through [observeActiveRooms] so any
     * live subscriber (the Home banner) reflects the refreshed set without
     * its own re-query.
     */
    suspend fun getActiveRooms(): GetActiveRoomsOutcome

    /**
     * Live view of the rooms the user is a member of, as known to this
     * client. Seeded by [getActiveRooms] and kept current by the local
     * room mutations ([createRoom] / [joinRoom] / [leaveRoom]) — so a join
     * or a forfeit reflects on the Home banner the instant it lands, with
     * no manual refresh. Starts empty until the first [getActiveRooms]
     * call hydrates it; the Home flow kicks that on arrival.
     *
     * This is a client-side projection, not a durable server stream — it
     * stays accurate for mutations this client makes, which is the case
     * Home cares about. A server-pushed source lands with persisted room
     * membership (todo B2).
     */
    fun observeActiveRooms(): Flow<List<Room>>

    /**
     * Open a live handle to the room's WebSocket. Auto-reconnects on
     * transport failures with exponential backoff (capped at ~16s).
     * Cancel every collector on the returned handle to close the socket.
     */
    fun connect(code: String): RoomConnectionHandle
}

sealed interface CreateRoomOutcome {
    data class Success(val room: Room) : CreateRoomOutcome
    data class InvalidMaxSeats(val message: String) : CreateRoomOutcome
    data class NotSignedIn(val cause: Throwable? = null) : CreateRoomOutcome
    data class NetworkError(val cause: Throwable) : CreateRoomOutcome
    data class Unknown(val cause: Throwable) : CreateRoomOutcome
}

sealed interface JoinRoomOutcome {
    /** Joined (new seat) OR was already a member (`alreadyJoined = true`). */
    data class Success(val room: Room, val alreadyJoined: Boolean) : JoinRoomOutcome
    data object NotFound : JoinRoomOutcome
    data object Full : JoinRoomOutcome
    data object NotJoinable : JoinRoomOutcome
    data class NotSignedIn(val cause: Throwable? = null) : JoinRoomOutcome
    data class NetworkError(val cause: Throwable) : JoinRoomOutcome
    data class Unknown(val cause: Throwable) : JoinRoomOutcome
}

sealed interface GetActiveRoomsOutcome {
    /** Empty list = no active rooms; the call still succeeded. */
    data class Success(val rooms: List<Room>) : GetActiveRoomsOutcome
    data class NotSignedIn(val cause: Throwable? = null) : GetActiveRoomsOutcome
    data class NetworkError(val cause: Throwable) : GetActiveRoomsOutcome
    data class Unknown(val cause: Throwable) : GetActiveRoomsOutcome
}

sealed interface LeaveRoomOutcome {
    data object Success : LeaveRoomOutcome
    data object NotFound : LeaveRoomOutcome
    data object NotInRoom : LeaveRoomOutcome
    data class NetworkError(val cause: Throwable) : LeaveRoomOutcome
    data class Unknown(val cause: Throwable) : LeaveRoomOutcome
}

sealed interface AddBotOutcome {
    data class Success(val room: Room) : AddBotOutcome
    data object NotFound : AddBotOutcome
    /** The caller isn't the host. */
    data object NotHost : AddBotOutcome
    data object Full : AddBotOutcome
    /** The room isn't in the lobby (e.g. already playing). */
    data object NotJoinable : AddBotOutcome
    data class NetworkError(val cause: Throwable) : AddBotOutcome
    data class Unknown(val cause: Throwable) : AddBotOutcome
}

sealed interface RemoveBotOutcome {
    data object Success : RemoveBotOutcome
    data object NotFound : RemoveBotOutcome
    data object NotHost : RemoveBotOutcome
    data class NetworkError(val cause: Throwable) : RemoveBotOutcome
    data class Unknown(val cause: Throwable) : RemoveBotOutcome
}

/**
 * State of the live WebSocket to a room. UI usually only cares about
 * [Connected.room] but renders the other states as banners ("connecting…",
 * "reconnecting, attempt 3").
 *
 *  - [Connecting] — opening the socket. Emits once at start of collection.
 *  - [Connected] — socket is alive; [room] is the latest snapshot.
 *  - [Reconnecting] — socket dropped; the client is backing off + retrying.
 *    [attempt] increments per failed retry so UI can show progress.
 *  - [Closed] — terminal. The server told us the room is gone, or the
 *    user explicitly stopped observing. No further events.
 */
sealed interface RoomConnection {
    data object Connecting : RoomConnection
    data class Connected(val room: Room) : RoomConnection
    data class Reconnecting(val attempt: Int, val cause: Throwable?) : RoomConnection
    data class Closed(val reason: ClosedReason) : RoomConnection
}

enum class ClosedReason {
    /** Server signalled `room_closed` (last member left / GC'd). */
    RoomDeleted,
    /** Server rejected the upgrade (not a member, unknown code, etc.) — terminal. */
    Rejected,
    /** The user cancelled the observe call themselves. */
    Cancelled,
}
