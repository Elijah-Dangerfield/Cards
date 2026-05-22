package com.dangerfield.cards.features.lobby.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.IdentityState
import com.dangerfield.cards.libraries.identity.awaitIdentity
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

/**
 * Drives the lobby. Three phases:
 *  1. Idle — show the create + join forms.
 *  2. Creating / Joining — the HTTP call is in-flight (block CTAs).
 *  3. InRoom — connected to a room via the WS; render the member list
 *     + the "Leave" button + the current ConnectionStatus banner.
 *
 * Transition machinery is intentionally simple: a single `connectionJob`
 * holds the WS subscription. Leave + sign-out + screen-pop all cancel
 * it. That keeps "one socket per VM lifetime" guaranteed without an
 * explicit lock.
 *
 * Why HTTP join → then observe (not implicit-join on the WS): join is
 * the spot where the seat allocator lives server-side. The socket
 * route refuses non-members on purpose so mutations flow through one
 * path. The VM hides that two-step from the user — they tap "Join"
 * once and see the lobby populate.
 */
@Inject
class LobbyViewModel(
    @Assisted private val prefilledCode: String?,
    private val rooms: RoomRepository,
    private val identity: IdentityRepository,
    private val appScope: AppCoroutineScope,
) : SEAViewModel<LobbyState, LobbyEvent, LobbyAction>(
    initialStateArg = LobbyState(codeInput = prefilledCode?.uppercase().orEmpty()),
) {

    private val logger = KLog.withTag("LobbyVM")
    private var connectionJob: Job? = null

    init {
        // Seed currentUserId so the UI knows who's host. awaitIdentity()
        // returns immediately when bootstrap is done (the common path
        // post-onboarding) and waits the brief cache-hydrate window
        // otherwise.
        viewModelScope.launch {
            val id = identity.awaitIdentity().userId
            takeAction(LobbyAction.IdentityResolved(id))
        }
        // Deep-link join path: the route opens with a code already
        // populated. We attempt the join automatically so the user
        // doesn't have to tap twice.
        if (!prefilledCode.isNullOrBlank()) {
            takeAction(LobbyAction.SubmitJoin)
        }
    }

    override suspend fun handleAction(action: LobbyAction) {
        when (action) {
            is LobbyAction.CodeChanged -> action.updateState {
                it.copy(codeInput = action.value.uppercase(), error = null)
            }

            LobbyAction.CreateRoom -> action.run {
                val current = state
                if (current.isBusy) return@run
                updateState { it.copy(creating = true, error = null) }
                when (val outcome = rooms.createRoom()) {
                    is CreateRoomOutcome.Success -> startConnection(outcome.room)
                    is CreateRoomOutcome.InvalidMaxSeats -> updateState {
                        it.copy(creating = false, error = outcome.message)
                    }
                    is CreateRoomOutcome.NotSignedIn -> updateState {
                        it.copy(creating = false, error = "Sign in first to create a room.")
                    }
                    is CreateRoomOutcome.NetworkError -> updateState {
                        it.copy(creating = false, error = "Couldn't reach the server.")
                    }
                    is CreateRoomOutcome.Unknown -> {
                        logger.w(outcome.cause) { "Create room failed" }
                        updateState { it.copy(creating = false, error = "Couldn't create a room.") }
                    }
                }
            }

            LobbyAction.SubmitJoin -> action.run {
                val current = state
                if (current.isBusy) return@run
                val code = current.codeInput.trim()
                if (code.isBlank()) {
                    updateState { it.copy(error = "Enter a room code to join.") }
                    return@run
                }
                updateState { it.copy(joining = true, error = null) }
                when (val outcome = rooms.joinRoom(code)) {
                    is JoinRoomOutcome.Success -> startConnection(outcome.room)
                    JoinRoomOutcome.NotFound -> updateState {
                        it.copy(joining = false, error = "No room with code $code.")
                    }
                    JoinRoomOutcome.Full -> updateState {
                        it.copy(joining = false, error = "That room is full.")
                    }
                    JoinRoomOutcome.NotJoinable -> updateState {
                        it.copy(joining = false, error = "That room isn't accepting players right now.")
                    }
                    is JoinRoomOutcome.NotSignedIn -> updateState {
                        it.copy(joining = false, error = "Sign in first to join a room.")
                    }
                    is JoinRoomOutcome.NetworkError -> updateState {
                        it.copy(joining = false, error = "Couldn't reach the server.")
                    }
                    is JoinRoomOutcome.Unknown -> {
                        logger.w(outcome.cause) { "Join failed" }
                        updateState { it.copy(joining = false, error = "Couldn't join.") }
                    }
                }
            }

            LobbyAction.Leave -> action.run {
                val code = state.room?.code ?: return@run
                connectionJob?.cancel()
                connectionJob = null
                updateState { it.copy(leaving = true) }
                val outcome = appScope.async { rooms.leaveRoom(code) }.await()
                when (outcome) {
                    LeaveRoomOutcome.Success,
                    LeaveRoomOutcome.NotFound,
                    LeaveRoomOutcome.NotInRoom,
                        -> resetToIdle("")
                    is LeaveRoomOutcome.NetworkError -> {
                        resetToIdle("Couldn't tell the server we left. Your seat will free up.")
                    }
                    is LeaveRoomOutcome.Unknown -> {
                        logger.w(outcome.cause) { "Leave failed" }
                        resetToIdle("")
                    }
                }
            }

            is LobbyAction.ConnectionUpdated -> action.updateState {
                when (val conn = action.connection) {
                    RoomConnection.Connecting -> it.copy(connectionStatus = ConnectionStatus.Connecting)
                    is RoomConnection.Connected -> it.copy(
                        room = conn.room,
                        connectionStatus = ConnectionStatus.Connected,
                        creating = false,
                        joining = false,
                        leaving = false,
                    )
                    is RoomConnection.Reconnecting -> it.copy(
                        connectionStatus = ConnectionStatus.Reconnecting(conn.attempt),
                    )
                    is RoomConnection.Closed -> when (conn.reason) {
                        ClosedReason.RoomDeleted -> it.also {
                            // Push terminal close → drop back to idle with a note.
                        }.copy(
                            room = null,
                            connectionStatus = ConnectionStatus.Disconnected,
                            error = "The room was closed.",
                            creating = false,
                            joining = false,
                            leaving = false,
                        )
                        ClosedReason.Rejected -> it.copy(
                            room = null,
                            connectionStatus = ConnectionStatus.Disconnected,
                            error = "Couldn't connect — try rejoining.",
                            creating = false,
                            joining = false,
                            leaving = false,
                        )
                        ClosedReason.Cancelled -> it.copy(
                            connectionStatus = ConnectionStatus.Disconnected,
                        )
                    }
                }
            }

            LobbyAction.DismissError -> action.updateState { it.copy(error = null) }

            is LobbyAction.IdentityResolved -> action.updateState {
                it.copy(currentUserId = action.userId)
            }

            LobbyAction.StartGame -> action.run {
                // Multiplayer gameplay sync is Phase 4.2 — the per-room WS
                // already exists for presence; plugging GameEngine into it
                // is the next chunk. Until then, the host can see the
                // start CTA but tapping it surfaces an honest "coming soon"
                // message so this doesn't look like a black hole.
                updateState { it.copy(error = "Multiplayer gameplay launches with the next update — invite your friends and stand by.") }
            }
        }
    }

    private suspend fun startConnection(room: Room) {
        connectionJob?.cancel()
        // Stage the room immediately so the UI flips to the in-room
        // view while the socket warms up. ConnectionUpdated will refine
        // status as events come in.
        takeAction(LobbyAction.ConnectionUpdated(RoomConnection.Connected(room)))
        connectionJob = viewModelScope.launch {
            // Wait for an authenticated identity (rare race: a fresh
            // user who created a room before the anonymous bootstrap
            // settled). Once we have one, observe the socket.
            identity.state.filterIsInstance<IdentityState.SignedIn>().first()
            rooms.observeRoom(room.code).collect { connection ->
                takeAction(LobbyAction.ConnectionUpdated(connection))
            }
        }
    }

    private suspend fun resetToIdle(error: String) {
        updateStateInternal {
            it.copy(
                room = null,
                creating = false,
                joining = false,
                leaving = false,
                connectionStatus = ConnectionStatus.Disconnected,
                error = error.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * Convenience for state mutations from outside the action receiver
     * (e.g. [startConnection]'s collect). Wraps the standard
     * updateState in a synthetic action so the SEA invariant holds.
     */
    private suspend fun updateStateInternal(f: (LobbyState) -> LobbyState) {
        // Reuse DismissError as a no-op carrier — it never produces
        // observable side effects beyond an updateState call.
        val carrier = LobbyAction.DismissError
        carrier.updateState { f(it) }
    }
}

// ---------- MVI types ----------

data class LobbyState(
    val codeInput: String = "",
    val creating: Boolean = false,
    val joining: Boolean = false,
    val leaving: Boolean = false,
    val room: Room? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val error: String? = null,
    /** Filled at init from IdentityRepository so the UI can tell who's host. */
    val currentUserId: String? = null,
) {
    val isBusy: Boolean get() = creating || joining || leaving
    val isInRoom: Boolean get() = room != null
    val canSubmitJoin: Boolean
        get() = !isBusy && !isInRoom && codeInput.trim().length in MIN_CODE_LENGTH..MAX_CODE_LENGTH
    val canCreate: Boolean
        get() = !isBusy && !isInRoom

    /** True when the current user owns this room (and a start-game CTA should appear). */
    val isHost: Boolean
        get() = room != null && currentUserId != null && room.hostUserId == currentUserId

    /** Host can start once at least one other player has joined. */
    val canStart: Boolean
        get() = isHost && (room?.members?.size ?: 0) >= 2

    companion object {
        // Server uses 6 chars exactly; allow 4..8 client-side so we
        // don't fight a future format bump.
        const val MIN_CODE_LENGTH = 4
        const val MAX_CODE_LENGTH = 8
    }
}

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data object Connecting : ConnectionStatus
    data object Connected : ConnectionStatus
    data class Reconnecting(val attempt: Int) : ConnectionStatus
}

sealed interface LobbyEvent

sealed interface LobbyAction {
    data class CodeChanged(val value: String) : LobbyAction
    data object CreateRoom : LobbyAction
    data object SubmitJoin : LobbyAction
    data object Leave : LobbyAction
    data object StartGame : LobbyAction
    data object DismissError : LobbyAction
    /** Internal — fired by the WS collector. */
    data class ConnectionUpdated(val connection: RoomConnection) : LobbyAction
    /** Internal — fired when bootstrap identity resolves. */
    data class IdentityResolved(val userId: String) : LobbyAction
}
