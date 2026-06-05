package com.dangerfield.cards.features.lobby.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.GameplayFrame
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import com.dangerfield.cards.libraries.rooms.RoomRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
    @Assisted private val autoCreate: Boolean,
    private val rooms: RoomRepository,
    private val auth: AuthRepository,
    private val appScope: AppCoroutineScope,
) : SEAViewModel<LobbyState, LobbyEvent, LobbyAction>(
    initialStateArg = LobbyState(codeInput = prefilledCode?.uppercase().orEmpty()),
) {

    private val logger = KLog.withTag("LobbyVM")
    private var connectionJob: Job? = null

    /**
     * Stashed across action handlers so [LobbyAction.StartGame] can
     * write a [ClientFrame.StartHand] over the same WS the connection
     * collector reads on.
     */
    private var currentHandle: RoomConnectionHandle? = null

    init {
        // Seed currentUserId so the UI knows who's host. current()
        // suspends until auth resolves (the common path post-onboarding
        // returns immediately; cache-hydrate window otherwise).
        viewModelScope.launch {
            val resolved = auth.current()
            if (resolved is AuthState.Authenticated) {
                takeAction(LobbyAction.IdentityResolved(resolved.userId))
            }
        }
        // Deep-link join path: the route opens with a code already
        // populated. We attempt the join automatically so the user
        // doesn't have to tap twice.
        if (!prefilledCode.isNullOrBlank()) {
            takeAction(LobbyAction.SubmitJoin)
        } else if (autoCreate) {
            // Friend Game "Create a room" path: spin up the room on entry
            // so the user lands seated with a shareable code.
            takeAction(LobbyAction.CreateRoom)
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
                        it.copy(creating = false, error = LobbyError.CreateInvalidMaxSeats(outcome.message))
                    }
                    is CreateRoomOutcome.NotSignedIn -> updateState {
                        it.copy(creating = false, error = LobbyError.CreateNotSignedIn)
                    }
                    is CreateRoomOutcome.NetworkError -> updateState {
                        it.copy(creating = false, error = LobbyError.CreateNetworkError)
                    }
                    is CreateRoomOutcome.Unknown -> {
                        logger.w(outcome.cause) { "Create room failed" }
                        updateState { it.copy(creating = false, error = LobbyError.CreateUnknownError) }
                    }
                }
            }

            LobbyAction.SubmitJoin -> action.run {
                val current = state
                if (current.isBusy) return@run
                val code = current.codeInput.trim()
                if (code.isBlank()) {
                    updateState { it.copy(error = LobbyError.JoinBlankCode) }
                    return@run
                }
                updateState { it.copy(joining = true, error = null) }
                when (val outcome = rooms.joinRoom(code)) {
                    is JoinRoomOutcome.Success -> startConnection(outcome.room)
                    JoinRoomOutcome.NotFound -> updateState {
                        it.copy(joining = false, error = LobbyError.JoinRoomNotFound(code))
                    }
                    JoinRoomOutcome.Full -> updateState {
                        it.copy(joining = false, error = LobbyError.JoinRoomFull)
                    }
                    JoinRoomOutcome.NotJoinable -> updateState {
                        it.copy(joining = false, error = LobbyError.JoinRoomNotAcceptingPlayers)
                    }
                    is JoinRoomOutcome.NotSignedIn -> updateState {
                        it.copy(joining = false, error = LobbyError.JoinNotSignedIn)
                    }
                    is JoinRoomOutcome.NetworkError -> updateState {
                        it.copy(joining = false, error = LobbyError.JoinNetworkError)
                    }
                    is JoinRoomOutcome.Unknown -> {
                        logger.w(outcome.cause) { "Join failed" }
                        updateState { it.copy(joining = false, error = LobbyError.JoinUnknownError) }
                    }
                }
            }

            LobbyAction.Leave -> action.run {
                val code = state.room?.code ?: return@run
                connectionJob?.cancel()
                connectionJob = null
                currentHandle = null
                updateState { it.copy(leaving = true) }
                val outcome = appScope.async { rooms.leaveRoom(code) }.await()
                when (outcome) {
                    LeaveRoomOutcome.Success,
                    LeaveRoomOutcome.NotFound,
                    LeaveRoomOutcome.NotInRoom,
                        -> resetToIdle(error = null)
                    is LeaveRoomOutcome.NetworkError -> {
                        resetToIdle(error = LobbyError.LeaveServerNotNotified)
                    }
                    is LeaveRoomOutcome.Unknown -> {
                        logger.w(outcome.cause) { "Leave failed" }
                        resetToIdle(error = null)
                    }
                }
            }

            is LobbyAction.ConnectionUpdated -> action.run {
                // Capture the previous effective host *before* the state
                // transitions so we can detect a host promotion on
                // member-list changes (a disconnect / leave / reconnect).
                val previousHost = state.effectiveHostUserId
                // Capture the applied snapshot directly. Re-reading `state`
                // after updateState races the derived stateFlow's
                // propagation, so the promotion check keys off the value we
                // just wrote rather than a possibly-stale read.
                var appliedState = state
                updateState {
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
                            ClosedReason.RoomDeleted -> it.copy(
                                room = null,
                                connectionStatus = ConnectionStatus.Disconnected,
                                error = LobbyError.RoomWasClosed,
                                creating = false,
                                joining = false,
                                leaving = false,
                            )
                            ClosedReason.Rejected -> it.copy(
                                room = null,
                                connectionStatus = ConnectionStatus.Disconnected,
                                error = LobbyError.ConnectRejected,
                                creating = false,
                                joining = false,
                                leaving = false,
                            )
                            ClosedReason.Cancelled -> it.copy(
                                connectionStatus = ConnectionStatus.Disconnected,
                            )
                        }
                    }.also { appliedState = it }
                }
                val newHost = appliedState.effectiveHostUserId
                if (previousHost != null && newHost != null && previousHost != newHost) {
                    val newHostMember = appliedState.room?.members?.firstOrNull { it.userId == newHost }
                    sendEvent(
                        LobbyEvent.HostPromoted(
                            newHostDisplayName = newHostMember?.displayName ?: "Someone",
                            isLocalUser = newHost == appliedState.currentUserId,
                        ),
                    )
                }
            }

            LobbyAction.DismissError -> action.updateState { it.copy(error = null) }

            is LobbyAction.IdentityResolved -> action.updateState {
                it.copy(currentUserId = action.userId)
            }

            LobbyAction.StartGame -> action.run {
                val current = state
                if (!current.canStart) return@run
                val code = current.room?.code ?: return@run
                val handle = currentHandle ?: return@run
                handle.send(ClientFrame.StartHand(newNonce()))
                // Host navigates eagerly; non-hosts follow when the
                // first GameStateSnapshot arrives via the gameplay
                // collector below.
                sendEvent(LobbyEvent.NavigateToMultiplayer(code))
            }

            LobbyAction.GameplaySnapshotReceived -> action.run {
                val current = state
                if (current.hasReceivedGameplaySnapshot) return@run
                updateState { it.copy(hasReceivedGameplaySnapshot = true) }
                // Host already navigated when they tapped Start; only
                // non-hosts need this auto-follow.
                if (!current.isHost) {
                    val code = current.room?.code ?: return@run
                    sendEvent(LobbyEvent.NavigateToMultiplayer(code))
                }
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun newNonce(): String = Uuid.random().toString()

    private suspend fun startConnection(room: Room) {
        connectionJob?.cancel()
        currentHandle = null
        // Stage the room immediately so the UI flips to the in-room
        // view while the socket warms up. ConnectionUpdated will refine
        // status as events come in.
        takeAction(LobbyAction.ConnectionUpdated(RoomConnection.Connected(room)))
        connectionJob = viewModelScope.launch {
            // Wait for an authenticated session (rare race: a fresh
            // user who created a room before the anonymous bootstrap
            // settled). Once we have one, observe the socket.
            auth.observe().filterIsInstance<AuthState.Authenticated>().first()
            val handle = rooms.connect(room.code)
            currentHandle = handle
            // Connection + gameplay collectors run in parallel inside
            // the same connectionJob so cancelling the job tears both
            // down via structured concurrency.
            launch {
                handle.connection.collect { connection ->
                    takeAction(LobbyAction.ConnectionUpdated(connection))
                }
            }
            launch {
                handle.gameplayFrames.collect { frame ->
                    // The first GameStateSnapshot signals "the hand
                    // has started" — non-hosts use this to auto-follow
                    // the host into the play screen. We don't care
                    // about other frames here; the play screen will
                    // re-subscribe through the same shared handle and
                    // pick up its own stream.
                    if (frame is GameplayFrame.StateSnapshot) {
                        takeAction(LobbyAction.GameplaySnapshotReceived)
                    }
                }
            }
        }
    }

    private suspend fun resetToIdle(error: LobbyError?) {
        updateStateInternal {
            it.copy(
                room = null,
                creating = false,
                joining = false,
                leaving = false,
                connectionStatus = ConnectionStatus.Disconnected,
                error = error,
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
    val error: LobbyError? = null,
    /** Filled at init from AuthRepository so the UI can tell who's host. */
    val currentUserId: String? = null,
    /** Set on the first GameStateSnapshot received for the current room.
     *  Used by [LobbyAction.GameplaySnapshotReceived] to navigate
     *  non-host clients into the play screen exactly once per session. */
    val hasReceivedGameplaySnapshot: Boolean = false,
) {
    val isBusy: Boolean get() = creating || joining || leaving
    val isInRoom: Boolean get() = room != null
    val canSubmitJoin: Boolean
        get() = !isBusy && !isInRoom && codeInput.trim().length in MIN_CODE_LENGTH..MAX_CODE_LENGTH
    val canCreate: Boolean
        get() = !isBusy && !isInRoom

    /**
     * The user who currently owns the room. Computed as the first
     * *connected* member — this gives implicit auto-promotion when the
     * server-tagged host disconnects (next connected member becomes
     * effective host). When the original host reconnects they may or
     * may not regain host depending on their seat order.
     */
    val effectiveHostUserId: String?
        get() = room?.members?.firstOrNull { it.isConnected }?.userId

    /** True when the current user owns this room (and a start-game CTA should appear). */
    val isHost: Boolean
        get() = effectiveHostUserId != null && effectiveHostUserId == currentUserId

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

/**
 * Inline error surfaced under the lobby form. Typed so the VM doesn't
 * hold raw user-facing copy — the screen resolves each variant through
 * Compose Multiplatform resources at render time.
 */
sealed interface LobbyError {
    /** Server rejected the create-room call because maxSeats was out of range. The server-provided message is passed through verbatim (the wire format may evolve before V1.x). */
    data class CreateInvalidMaxSeats(val message: String) : LobbyError
    data object CreateNotSignedIn : LobbyError
    data object CreateNetworkError : LobbyError
    data object CreateUnknownError : LobbyError
    data object JoinBlankCode : LobbyError
    data class JoinRoomNotFound(val code: String) : LobbyError
    data object JoinRoomFull : LobbyError
    data object JoinRoomNotAcceptingPlayers : LobbyError
    data object JoinNotSignedIn : LobbyError
    data object JoinNetworkError : LobbyError
    data object JoinUnknownError : LobbyError
    data object LeaveServerNotNotified : LobbyError
    data object RoomWasClosed : LobbyError
    data object ConnectRejected : LobbyError
    data object StartGameComingSoon : LobbyError
}

sealed interface LobbyEvent {
    /** Host tapped Start, or a non-host saw the first
     *  GameStateSnapshot — either way it's time to leave the lobby
     *  and render the play screen. */
    data class NavigateToMultiplayer(val roomCode: String) : LobbyEvent

    /** The effective host changed mid-session (typically because the
     *  previous host disconnected). Screen surfaces a short banner. */
    data class HostPromoted(
        val newHostDisplayName: String,
        val isLocalUser: Boolean,
    ) : LobbyEvent
}

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
    /** Internal — fired on the first gameplay-frames StateSnapshot per
     *  room so non-host clients can auto-follow the host into the
     *  play screen. */
    data object GameplaySnapshotReceived : LobbyAction
}
