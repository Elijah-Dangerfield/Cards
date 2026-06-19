package com.cards.integration.helpers

import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.GameplayFrame
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * A gameplay-level view over a real [RoomConnectionHandle]: background collectors
 * keep the socket alive and buffer inbound game-state snapshots + intent acks, so
 * a test can `startHand`, read the next snapshot matching a predicate, and submit
 * intents — all over the real wire against the real server engine.
 *
 * Snapshots/acks are buffered (replay) so a slightly-late read still sees them;
 * use phase-distinguishing predicates (e.g. `street == Complete`,
 * `actingSeatIndex != null`) rather than matching a stale buffered state.
 */
class GameplaySession(
    private val handle: RoomConnectionHandle,
    scope: CoroutineScope,
) {
    private val snapshots = MutableSharedFlow<GameState>(replay = 32, extraBufferCapacity = 64)
    private val acks = MutableSharedFlow<GameplayFrame.IntentAck>(replay = 32, extraBufferCapacity = 64)
    private val emotes = MutableSharedFlow<GameplayFrame.EmojiBlast>(replay = 32, extraBufferCapacity = 64)
    private val connection = MutableStateFlow<RoomConnection?>(null)

    init {
        scope.launch { handle.connection.collect { connection.value = it } }
        scope.launch {
            handle.gameplayFrames.collect { frame ->
                when (frame) {
                    is GameplayFrame.StateSnapshot -> snapshots.emit(frame.state)
                    is GameplayFrame.IntentAck -> acks.emit(frame)
                    is GameplayFrame.EmojiBlast -> emotes.emit(frame)
                    is GameplayFrame.Event -> Unit
                }
            }
        }
    }

    suspend fun awaitConnected(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Room =
        withTimeout(timeoutMs) {
            connection.filterIsInstance<RoomConnection.Connected>().first().room
        }

    // Forward-only cursor so a buffered (replayed) older snapshot is never
    // re-matched — keyed on (handNumber, lastSequence), mirroring the client's
    // own out-of-order guard (lastSequence resets per hand).
    private var cursorHand = -1
    private var cursorSeq = -1L

    suspend fun startHand() = handle.send(ClientFrame.StartHand(clientNonce = newNonce()))

    suspend fun requestNextHand() = handle.send(ClientFrame.RequestNextHand(clientNonce = newNonce()))

    /**
     * The next game-state snapshot satisfying [predicate] that is strictly newer
     * than the last one returned (so repeated calls walk the hand forward).
     */
    suspend fun nextSnapshot(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        predicate: (GameState) -> Boolean = { true },
    ): GameState = withTimeout(timeoutMs) {
        snapshots.first { it.isAfterCursor() && predicate(it) }.also {
            cursorHand = it.handNumber
            cursorSeq = it.lastSequence
        }
    }

    private fun GameState.isAfterCursor(): Boolean =
        handNumber > cursorHand || (handNumber == cursorHand && lastSequence > cursorSeq)

    /** Blast a table emote to the rest of the room. */
    suspend fun sendEmote(emoji: String) =
        handle.send(ClientFrame.SendEmoji(emoji = emoji, clientNonce = newNonce()))

    /** The next inbound emote blast (from any seat). */
    suspend fun nextEmote(timeoutMs: Long = DEFAULT_TIMEOUT_MS): GameplayFrame.EmojiBlast =
        withTimeout(timeoutMs) { emotes.first() }

    /** Submit [intent] and return the server's correlated ack (accepted or rejected). */
    suspend fun submit(
        intent: PlayerIntent,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): GameplayFrame.IntentAck {
        val nonce = newNonce()
        handle.send(ClientFrame.SubmitIntent(intent = intent, clientNonce = nonce))
        return withTimeout(timeoutMs) { acks.first { it.clientNonce == nonce } }
    }

    private fun newNonce(): String = UUID.randomUUID().toString()
}
