package com.dangerfield.cards.features.lobby.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.equippedTableCosmetics
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.avatarBackgroundColorOrNull
import com.dangerfield.cards.libraries.identity.profile.avatarEmojiOrNull
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.rooms.AddBotOutcome
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.GameplayFrame
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.mergeStakesFrom
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
    @Assisted private val maxSeats: Int?,
    @Assisted private val buyIn: Long?,
    @Assisted private val open: Boolean,
    @Assisted private val pickedFeltProductId: String?,
    @Assisted private val pickedCardBackProductId: String?,
    private val rooms: RoomRepository,
    private val auth: AuthRepository,
    private val profile: ProfileRepository,
    private val equipment: EquipmentRepository,
    private val chips: ChipsRepository,
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
        // Observe the local profile so our own seat renders the player's
        // chosen avatar (emoji + colour) instead of a name initial. Other
        // seats stay on initials until the room model carries avatar data
        // (see docs/todo.md).
        viewModelScope.launch {
            profile.observe().collect { p ->
                takeAction(
                    LobbyAction.ProfileResolved(
                        avatarEmoji = p.avatarEmojiOrNull,
                        avatarColorHex = p.avatarBackgroundColorOrNull,
                        isFallback = p is Profile.Fallback,
                    ),
                )
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
                // SHOP-5: pin the host's *picked* felt + card back onto the room so
                // every player sees the host's chosen table look. The create screen
                // pre-seeds its picker from the equipped look (SHOP-3), so a route
                // that carries a pick uses it directly; a create path with no pick
                // (e.g. a future quick-create) falls back to reading the equipped
                // cosmetics here. Best-effort — a read failure falls back to no
                // override (each player's own cosmetic), never blocks room creation.
                val equipped = if (pickedFeltProductId == null || pickedCardBackProductId == null) {
                    Catching { equippedTableCosmetics(equipment.observeEquipped().first()) }.getOrNull()
                } else {
                    null
                }
                val feltProductId = pickedFeltProductId ?: equipped?.feltProductId
                val cardBackProductId = pickedCardBackProductId ?: equipped?.cardBackProductId
                when (
                    val outcome = rooms.createRoom(
                        maxSeats = maxSeats,
                        buyIn = buyIn,
                        open = open,
                        feltProductId = feltProductId,
                        cardBackProductId = cardBackProductId,
                    )
                ) {
                    is CreateRoomOutcome.Success -> startConnection(outcome.room)
                    is CreateRoomOutcome.InvalidMaxSeats -> updateState {
                        it.copy(creating = false, error = LobbyError.CreateInvalidMaxSeats(outcome.message))
                    }
                    is CreateRoomOutcome.NotSignedIn -> updateState {
                        // Off a cached/offline profile (Profile.Fallback) the server
                        // session was never minted, so a 401 here is a connection
                        // problem, not an account-less state (AUTH-6). Only a real
                        // signed-in account whose token chain ran dry reads as
                        // "sign in first."
                        it.copy(
                            creating = false,
                            error = if (it.isFallbackProfile) {
                                LobbyError.CreateNetworkError
                            } else {
                                LobbyError.CreateNotSignedIn
                            },
                        )
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
                    JoinRoomOutcome.NotFound -> {
                        // A prefilled-code join is the PrivateJoin → Lobby funnel:
                        // the input screen already popped, so showing the error here
                        // strands the user on a dead spinner. Route them back to the
                        // code-entry screen to fix and retry (CARDS-28). A non-prefill
                        // join (no input screen behind us) keeps the inline error.
                        if (!prefilledCode.isNullOrBlank()) {
                            updateState { it.copy(joining = false) }
                            sendEvent(LobbyEvent.JoinCodeRejected(code))
                        } else {
                            updateState {
                                it.copy(joining = false, error = LobbyError.JoinRoomNotFound(code))
                            }
                        }
                    }
                    JoinRoomOutcome.Full -> updateState {
                        it.copy(joining = false, error = LobbyError.JoinRoomFull)
                    }
                    is JoinRoomOutcome.OverBalance -> updateState {
                        it.copy(joining = false, error = LobbyError.JoinOverBalance(outcome.message))
                    }
                    JoinRoomOutcome.NotJoinable -> updateState {
                        it.copy(joining = false, error = LobbyError.JoinRoomNotAcceptingPlayers)
                    }
                    is JoinRoomOutcome.NotSignedIn -> updateState {
                        // See CreateRoom above: a Fallback profile means no minted
                        // session, so this 401 is a connection problem (AUTH-6).
                        it.copy(
                            joining = false,
                            error = if (it.isFallbackProfile) {
                                LobbyError.JoinNetworkError
                            } else {
                                LobbyError.JoinNotSignedIn
                            },
                        )
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
                val room = state.room ?: return@run
                val code = room.code
                connectionJob?.cancel()
                connectionJob = null
                currentHandle = null
                updateState { it.copy(leaving = true) }
                // MP-27 / MP-29: leaving a real-chip room (here usually after an
                // opponent-left kick collapsed the play screen back to this
                // lobby) must reconcile the wallet, or the client keeps showing
                // the buy-in as escrowed until the next foreground. The leave
                // call itself now cashes out synchronously and returns the
                // authoritative balance, so we apply that directly below — no
                // speculative sync racing the server settlement (CARDS-5Q).
                // Fire-and-forget on appScope so it survives the lobby popping.
                val outcome = appScope.async { rooms.leaveRoom(code) }.await()
                reconcileWalletAfterRoom(room, outcome)
                when (outcome) {
                    is LeaveRoomOutcome.Success,
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
                            // A lobby presence snapshot carries the converged
                            // member list but may report buyIn = 0; merging
                            // keeps that live member list while pinning the
                            // stakes to the real value the HTTP join/create
                            // response staged, so a joiner never sees a $0
                            // buy-in (MP-24).
                            room = conn.room.mergeStakesFrom(it.room),
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
                            ClosedReason.ReconnectFailed -> it.copy(
                                connectionStatus = ConnectionStatus.Disconnected,
                                error = LobbyError.ConnectionLost,
                                creating = false,
                                joining = false,
                                leaving = false,
                            )
                            ClosedReason.Cancelled -> it.copy(
                                connectionStatus = ConnectionStatus.Disconnected,
                            )
                            // A heads-up match-over (MP-14) is handled on the play
                            // screen; if it ever reaches the lobby's socket the room
                            // is gone, so treat it like RoomDeleted.
                            is ClosedReason.MatchOver -> it.copy(
                                room = null,
                                connectionStatus = ConnectionStatus.Disconnected,
                                error = LobbyError.RoomWasClosed,
                                creating = false,
                                joining = false,
                                leaving = false,
                            )
                            // A frame this client can't parse (ENG-7): the room is
                            // unusable for this build, so close it out like the
                            // room being gone rather than spinning on a dead lobby.
                            ClosedReason.IncompatibleVersion -> it.copy(
                                room = null,
                                connectionStatus = ConnectionStatus.Disconnected,
                                error = LobbyError.RoomWasClosed,
                                creating = false,
                                joining = false,
                                leaving = false,
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

            is LobbyAction.AddBot -> action.run {
                val current = state
                // Host-only, lobby-only. The button is gated in the UI; this is
                // the authority check that matters.
                if (!current.isHost) return@run
                val code = current.room?.code ?: return@run
                when (val outcome = rooms.addBot(code, action.seatIndex)) {
                    // Success needs no local mutation — the new bot seat arrives
                    // over the socket as the next room Snapshot.
                    is AddBotOutcome.Success -> Unit
                    is AddBotOutcome.NetworkError ->
                        updateState { it.copy(error = LobbyError.BotActionFailed) }
                    is AddBotOutcome.Unknown -> {
                        logger.w(outcome.cause) { "Add bot failed" }
                        updateState { it.copy(error = LobbyError.BotActionFailed) }
                    }
                    AddBotOutcome.Full,
                    AddBotOutcome.NotHost,
                    AddBotOutcome.NotJoinable,
                    AddBotOutcome.NotFound,
                        -> updateState { it.copy(error = LobbyError.BotActionFailed) }
                }
            }

            is LobbyAction.RemoveBot -> action.run {
                val current = state
                if (!current.isHost) return@run
                val code = current.room?.code ?: return@run
                // Fire-and-forget: the removed seat disappears via the socket
                // Snapshot. Surface only hard failures.
                when (rooms.removeBot(code, action.botUserId)) {
                    com.dangerfield.cards.libraries.rooms.RemoveBotOutcome.Success,
                    com.dangerfield.cards.libraries.rooms.RemoveBotOutcome.NotFound,
                        -> Unit
                    else -> updateState { it.copy(error = LobbyError.BotActionFailed) }
                }
            }

            LobbyAction.DismissError -> action.updateState { it.copy(error = null) }

            is LobbyAction.IdentityResolved -> action.updateState {
                it.copy(currentUserId = action.userId)
            }

            is LobbyAction.ProfileResolved -> action.updateState {
                it.copy(
                    localAvatarEmoji = action.avatarEmoji,
                    localAvatarColorHex = action.avatarColorHex,
                    isFallbackProfile = action.isFallback,
                )
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
                // A host who tapped Start already navigated, so normally only
                // non-hosts auto-follow here. But a server-dealt table (Open to
                // anyone) deals itself with no Start tap, so the host follows the
                // first snapshot in too.
                if (!current.isHost || current.room?.isServerDealt == true) {
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

    /**
     * Reconcile the authoritative wallet after leaving a real-chip room
     * (MP-27 / MP-29). The [LeaveRoomOutcome.Success.settledBalance] the leave
     * returned is the server's post-cash-out balance, so we apply it directly —
     * the leave call *is* the reconcile, no speculative sync racing the
     * settlement commit (CARDS-5Q). When the leave settled nothing (a free table,
     * or an all-in-live deferral whose balance lands later over the socket) we
     * fall back to a sync so a deferred settlement still reflects. No-op for a
     * free table ([Room.buyIn] == 0). Fire-and-forget on [appScope] so it
     * completes even if the lobby screen pops.
     */
    private fun reconcileWalletAfterRoom(room: Room, outcome: LeaveRoomOutcome) {
        if (room.buyIn <= 0L) return
        val settledBalance = (outcome as? LeaveRoomOutcome.Success)?.settledBalance
        appScope.launch {
            Catching {
                if (settledBalance != null) {
                    chips.setBalance(settledBalance)
                } else {
                    chips.sync()
                }
            }.onFailure { e -> logger.w(e) { "wallet reconcile after leaving room failed" } }
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
    /** Local player's avatar (from ProfileRepository) so our own seat renders
     *  the chosen emoji/colour. Other seats lack this until the room model
     *  carries per-member avatar data. */
    val localAvatarEmoji: String? = null,
    val localAvatarColorHex: String? = null,
    /** True when the resolved profile is a [Profile.Fallback] — no confirmed
     *  server session (offline cold boot off a cached profile, or pre-auth
     *  bootstrap). Used to recolor a `NotSignedIn` create/join failure as a
     *  connection problem rather than an account-less state (AUTH-6). */
    val isFallbackProfile: Boolean = false,
    /** Set on the first GameStateSnapshot received for the current room.
     *  Used by [LobbyAction.GameplaySnapshotReceived] to navigate
     *  non-host clients into the play screen exactly once per session. */
    val hasReceivedGameplaySnapshot: Boolean = false,
) {
    val isBusy: Boolean get() = creating || joining || leaving
    val isInRoom: Boolean get() = room != null

    /**
     * A create-room call failed and we're not in a room. The lobby is
     * only ever entered to create or join, so a Create* error with no
     * room means the "Setting up…" path dead-ended — show the full-screen
     * retry state instead of a stranded spinner (CARDS-2E).
     */
    val createError: LobbyError?
        get() = if (!isInRoom && !creating) {
            error?.takeIf {
                it is LobbyError.CreateNetworkError ||
                    it is LobbyError.CreateUnknownError ||
                    it is LobbyError.CreateNotSignedIn ||
                    it is LobbyError.CreateInvalidMaxSeats
            }
        } else {
            null
        }
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
        get() = room?.members?.firstOrNull { it.isConnected && !it.isBot }?.userId

    /** True when the current user owns this room (and a start-game CTA should appear). */
    val isHost: Boolean
        get() = effectiveHostUserId != null && effectiveHostUserId == currentUserId

    /**
     * Host can start once at least one other player has joined — but only on a
     * host-dealt (Private) table. A server-dealt table (Open to anyone) deals
     * itself, so there's no Start to tap.
     */
    val canStart: Boolean
        get() = isHost && room?.isServerDealt != true && (room?.members?.size ?: 0) >= 2

    /** True when this table deals itself (Open to anyone) rather than waiting on the host's Start. */
    val isServerDealtTable: Boolean
        get() = room?.isServerDealt == true

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
    data class JoinOverBalance(val message: String) : LobbyError
    data object JoinRoomNotAcceptingPlayers : LobbyError
    data object JoinNotSignedIn : LobbyError
    data object JoinNetworkError : LobbyError
    data object JoinUnknownError : LobbyError
    data object LeaveServerNotNotified : LobbyError
    data object RoomWasClosed : LobbyError
    data object ConnectRejected : LobbyError
    data object ConnectionLost : LobbyError
    data object StartGameComingSoon : LobbyError
    /** Add-/remove-bot call failed (full, network, etc.). */
    data object BotActionFailed : LobbyError
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

    /** A deep-link / prefilled-code join hit an unknown room. Routes the user
     *  back to the code-entry screen with the bad code so they can fix and
     *  retry, rather than stranding them on a dead lobby spinner (CARDS-28). */
    data class JoinCodeRejected(val code: String) : LobbyEvent
}

sealed interface LobbyAction {
    data class CodeChanged(val value: String) : LobbyAction
    data object CreateRoom : LobbyAction
    data object SubmitJoin : LobbyAction
    data object Leave : LobbyAction
    data object StartGame : LobbyAction
    /** Host taps an empty seat's "Add a bot". [seatIndex] targets that seat. */
    data class AddBot(val seatIndex: Int) : LobbyAction
    /** Host taps a seated bot to remove it. */
    data class RemoveBot(val botUserId: String) : LobbyAction
    data object DismissError : LobbyAction
    /** Internal — fired by the WS collector. */
    data class ConnectionUpdated(val connection: RoomConnection) : LobbyAction
    /** Internal — fired when bootstrap identity resolves. */
    data class IdentityResolved(val userId: String) : LobbyAction

    data class ProfileResolved(
        val avatarEmoji: String?,
        val avatarColorHex: String?,
        val isFallback: Boolean,
    ) : LobbyAction
    /** Internal — fired on the first gameplay-frames StateSnapshot per
     *  room so non-host clients can auto-follow the host into the
     *  play screen. */
    data object GameplaySnapshotReceived : LobbyAction
}
