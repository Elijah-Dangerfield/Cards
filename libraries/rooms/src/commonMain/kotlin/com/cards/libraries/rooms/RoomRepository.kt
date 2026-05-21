package com.dangerfield.cards.libraries.rooms

import kotlinx.coroutines.flow.Flow

/**
 * Single client surface for the multiplayer room lifecycle.
 * Wraps the HTTP routes (create / join / leave) and the per-room
 * WebSocket so feature code never has to think about the split.
 *
 *  - [createRoom] / [joinRoom] / [leaveRoom] are one-shot HTTP calls
 *    that return a sealed outcome.
 *  - [observeRoom] opens (and reconnects) a WebSocket and emits
 *    [RoomConnection] state transitions: Connecting → Connected(room)
 *    → Reconnecting → Connected(room) → ... The flow never throws;
 *    transport failures surface as [RoomConnection.Reconnecting].
 *
 * Lifecycle: observeRoom holds the connection for the duration of the
 * collection. Cancelling the collector closes the socket.
 *
 * Membership: observeRoom assumes the user is already a member of the
 * room. Call [joinRoom] first; the LobbyScreen does this in sequence
 * (join → observe).
 */
interface RoomRepository {

    suspend fun createRoom(maxSeats: Int? = null): CreateRoomOutcome

    suspend fun joinRoom(code: String): JoinRoomOutcome

    suspend fun leaveRoom(code: String): LeaveRoomOutcome

    /**
     * Rooms the signed-in user is currently a member of. Called on cold
     * launch by the home/launch flow so we can offer "rejoin / forfeit"
     * before silently stranding a player whose previous session left a
     * seat warm via the `disconnectedAt` grace timer.
     */
    suspend fun getActiveRooms(): GetActiveRoomsOutcome

    /**
     * Live connection to a room's WebSocket. Auto-reconnects on
     * transport failures with exponential backoff (capped at ~16s).
     * Cancel the collector to close the socket.
     */
    fun observeRoom(code: String): Flow<RoomConnection>
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
