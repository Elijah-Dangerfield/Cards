package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Deck
import com.dangerfield.cards.libraries.gameplay.GameEngine
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.GameplayFrame
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.merge
import kotlin.random.Random

/**
 * A [RoomConnectionHandle] that answers [ClientFrame.StartHand] /
 * [ClientFrame.SubmitIntent] / [ClientFrame.RequestNextHand] by driving a **real**
 * [GameEngine], so client-side tests can cover full turn cycles without booting
 * Ktor. [FakeRoomConnectionHandle] only replays canned frames the test hand-feeds;
 * this server actually *plays* — it owns the authoritative [GameState], applies the
 * engine on every submit, and fans the resulting frames back out the way the real
 * server does (snapshot → events → ack).
 *
 * It mirrors the server [com.dangerfield.cards.server.game.GameSession]'s relevant
 * mechanics — seat construction from occupants, button rotation, the same actor /
 * turn / nonce gates — but stays in the test source set so it pulls in no server
 * (Exposed / Ktor / OTel) dependencies. The contract it speaks is the wire contract;
 * the session under test can't tell it from production.
 *
 * Wire-order contract (matched to `RoomSocketRoutes.handleClientFrame`):
 *  1. apply the engine step,
 *  2. push a [GameplayFrame.StateSnapshot] of the new state,
 *  3. push one [GameplayFrame.Event] per engine event (sequence-stamped),
 *  4. push a [GameplayFrame.IntentAck] keyed by the frame's `clientNonce`.
 *
 * A rejected mutation skips steps 2–3 and acks with `accepted = false`.
 *
 * @param occupants seats to deal in, in any order (sorted by index on each deal).
 * @param settings table stakes; defaults to [RoomSettings.Default].
 * @param deckFactory per-hand deck. Defaults to a seeded shuffle; pass [stackedDeck]
 *   to force exact hole/board cards.
 * @param localUserId the seat the client under test drives. Every *other* occupant
 *   is an opponent the server auto-plays via [opponentPolicy] the moment it lands on
 *   their turn — mirroring the production bot driver, so the client only ever submits
 *   its own seat and still sees full turn cycles.
 * @param opponentPolicy how the server resolves an opponent's turn. Defaults to
 *   check-or-call (a passive opponent who never folds, so hands reach showdown).
 */
class FakeRoomServer(
    private val occupants: List<ServerSeat>,
    private val settings: RoomSettings = RoomSettings.Default,
    private val random: Random = Random(42L),
    private val deckFactory: (handNumber: Int) -> Deck = { Deck.shuffled(random) },
    private val localUserId: String = MP_LOCAL_USER,
    private val opponentPolicy: OpponentPolicy = OpponentPolicy.CheckOrCall,
) : RoomConnectionHandle {

    private val _connection = MutableSharedFlow<RoomConnection>(replay = 1, extraBufferCapacity = 8)

    private val _latestState = MutableStateFlow<GameplayFrame.StateSnapshot?>(null)
    private val _frames = MutableSharedFlow<GameplayFrame>(replay = 0, extraBufferCapacity = 64)

    private var state: GameState? = null
    private var seq: Long = 0L
    private val processedNonces = ArrayDeque<String>()

    /** Every [ClientFrame] the session sent, for spy assertions. */
    val received: MutableList<ClientFrame> = mutableListOf()

    /** The authoritative [GameState] the server has computed, or null pre-deal. */
    val currentState: GameState? get() = state

    override val connection: SharedFlow<RoomConnection> = _connection.asSharedFlow()
    override val gameplayFrames: Flow<GameplayFrame> =
        merge(_latestState.filterNotNull(), _frames)

    override suspend fun send(frame: ClientFrame) {
        received += frame
        when (frame) {
            is ClientFrame.StartHand -> startHand(frame.clientNonce)
            is ClientFrame.RequestNextHand -> startHand(frame.clientNonce)
            is ClientFrame.SubmitIntent -> submitIntent(frame)
            is ClientFrame.Rebuy -> ack(frame.clientNonce, accepted = false, error = "rebuy unsupported in fake")
            is ClientFrame.SendEmoji -> emitEmoji(frame)
        }
    }

    /** Push a lobby connection state to the session (e.g. [RoomConnection.Connected]). */
    suspend fun pushConnection(connection: RoomConnection) {
        _connection.emit(connection)
    }

    private suspend fun startHand(clientNonce: String) {
        if (clientNonce in processedNonces) {
            ack(clientNonce, accepted = true)
            return
        }
        val prior = state
        if (prior != null && prior.street != BettingRound.Complete) {
            ack(clientNonce, accepted = false, error = "hand already in progress")
            return
        }
        val returning = occupants.filter { occ ->
            prior == null || prior.seats.firstOrNull { it.playerId == occ.userId }?.let { it.stack > 0 } ?: true
        }
        if (returning.size < 2) {
            ack(clientNonce, accepted = false, error = "need at least 2 occupants")
            return
        }

        val handNumber = (prior?.handNumber ?: 0) + 1
        val seats = returning.sortedBy { it.seatIndex }.map { occ ->
            val priorSeat = prior?.seats?.firstOrNull { it.playerId == occ.userId }
            Seat(
                index = occ.seatIndex,
                playerId = occ.userId,
                displayName = occ.displayName,
                stack = priorSeat?.stack ?: settings.startingStack,
                seatStatus = SeatStatus.Active,
                handParticipation = HandParticipation.InHand,
                isBot = occ.isBot,
            )
        }
        val sortedIndexes = seats.map { it.index }.sorted()
        val button = if (prior == null) {
            sortedIndexes.first()
        } else {
            sortedIndexes.firstOrNull { it > prior.buttonSeatIndex } ?: sortedIndexes.first()
        }

        val result = GameEngine.startHand(
            settings = settings,
            seats = seats,
            handNumber = handNumber,
            buttonSeatIndex = button,
            deck = deckFactory(handNumber),
        )
        state = result.state
        recordNonce(clientNonce)
        emitSnapshot(result.state)
        result.events.forEach { emitEvent(it) }
        ack(clientNonce, accepted = true)
        advanceOpponents()
    }

    private suspend fun submitIntent(frame: ClientFrame.SubmitIntent) {
        val current = state
        if (current == null) {
            ack(frame.clientNonce, accepted = false, error = "no active hand")
            return
        }
        if (frame.clientNonce in processedNonces) {
            ack(frame.clientNonce, accepted = true)
            return
        }
        val actor = occupants.firstOrNull { it.seatIndex == frame.intent.seatIndex }
        if (actor == null) {
            ack(frame.clientNonce, accepted = false, error = "not seated in this room")
            return
        }
        if (current.actingSeatIndex != frame.intent.seatIndex) {
            ack(frame.clientNonce, accepted = false, error = "not your turn")
            return
        }
        val result = try {
            GameEngine.applyIntent(current, frame.intent)
        } catch (e: IllegalArgumentException) {
            ack(frame.clientNonce, accepted = false, error = e.message ?: "illegal intent")
            return
        }
        state = result.state
        recordNonce(frame.clientNonce)
        emitSnapshot(result.state)
        result.events.forEach { emitEvent(it) }
        ack(frame.clientNonce, accepted = true)
        advanceOpponents()
    }

    /**
     * Resolve every consecutive opponent turn via [opponentPolicy], fanning each
     * resulting snapshot + events out in order, until the local seat is on the
     * clock or the hand completes. Mirrors the server's bot driver: the client
     * only submits its own seat, yet a full hand still cycles.
     */
    private suspend fun advanceOpponents() {
        var guard = 0
        while (guard++ < MAX_OPPONENT_STEPS) {
            val current = state ?: return
            if (current.street == BettingRound.Complete) return
            val acting = current.actingSeatIndex ?: return
            val seat = current.seats.firstOrNull { it.index == acting } ?: return
            if (seat.playerId == localUserId) return

            val intent = opponentPolicy.intentFor(current, acting)
            val result = try {
                GameEngine.applyIntent(current, intent)
            } catch (_: IllegalArgumentException) {
                GameEngine.applyIntent(current, PlayerIntent.Fold(seatIndex = acting))
            }
            state = result.state
            emitSnapshot(result.state)
            result.events.forEach { emitEvent(it) }
        }
    }

    private suspend fun emitEmoji(frame: ClientFrame.SendEmoji) {
        val seat = state?.seats?.firstOrNull { it.playerId == localUserId }
        if (seat == null) {
            ack(frame.clientNonce, accepted = false, error = "not seated in this room")
            return
        }
        _frames.emit(GameplayFrame.EmojiBlast(seatIndex = seat.index, emoji = frame.emoji))
        ack(frame.clientNonce, accepted = true)
    }

    private suspend fun emitSnapshot(newState: GameState) {
        _latestState.value = GameplayFrame.StateSnapshot(newState)
    }

    private suspend fun emitEvent(event: com.dangerfield.cards.libraries.gameplay.GameEvent) {
        _frames.emit(GameplayFrame.Event(seq = ++seq, event = event))
    }

    private suspend fun ack(clientNonce: String, accepted: Boolean, error: String? = null) {
        _frames.emit(GameplayFrame.IntentAck(clientNonce = clientNonce, accepted = accepted, error = error))
    }

    private fun recordNonce(nonce: String) {
        if (processedNonces.size >= NONCE_RING_CAPACITY) processedNonces.removeFirst()
        processedNonces.addLast(nonce)
    }

    private companion object {
        const val NONCE_RING_CAPACITY = 64
        const val MAX_OPPONENT_STEPS = 200
    }
}

/**
 * How [FakeRoomServer] resolves an opponent seat's turn. The default
 * [CheckOrCall] is a passive opponent — it never folds, so calling the human
 * down drives the hand to showdown. Supply a custom policy for scenarios that
 * need an opponent to bet or fold on cue.
 */
fun interface OpponentPolicy {
    fun intentFor(state: GameState, seatIndex: Int): PlayerIntent

    companion object {
        val CheckOrCall = OpponentPolicy { state, seatIndex ->
            val seat = state.seats.first { it.index == seatIndex }
            if (state.currentBetThisStreet > seat.contributedThisStreet) {
                PlayerIntent.Call(seatIndex = seatIndex)
            } else {
                PlayerIntent.Check(seatIndex = seatIndex)
            }
        }

        val AlwaysFold = OpponentPolicy { _, seatIndex -> PlayerIntent.Fold(seatIndex = seatIndex) }
    }
}

/**
 * A seat to deal into [FakeRoomServer], in the shape the server needs — the
 * client's `SeatOccupant` carries presentation state this fake doesn't model.
 */
data class ServerSeat(
    val seatIndex: Int,
    val userId: String,
    val displayName: String = userId,
    val isBot: Boolean = false,
)
