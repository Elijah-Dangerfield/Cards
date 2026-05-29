package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.di.ServerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * One [GameSession] per active room. Lifecycle:
 *
 *  - [startHand] creates (or reuses) a session for the room and runs
 *    the engine's `startHand` under the session's mutex. The room
 *    transition to `RoomStatus.Playing` is the caller's job (lives in
 *    `RoomService.markPlaying`).
 *  - [applyIntent] / [requestNextHand] proxy into the session.
 *  - [observeSession] is the socket publisher's subscription point —
 *    fires once on subscribe with whatever's currently registered, then
 *    again whenever sessions come and go. Lets each connected client
 *    pick up game frames the moment the host's StartHand creates the
 *    session, without polling.
 *  - [peek] is the synchronous lookup; mostly useful in tests + one-off
 *    code paths.
 *  - [end] drops the session.
 *
 * Threading: a Mutex serializes registry mutations (create / drop) so
 * the observable map stays consistent. Per-session mutation is
 * serialized by a Mutex inside [GameSession] itself. The two layers
 * handle different concerns — don't merge.
 *
 * Bots: the registry doesn't know or care about bots; they're just
 * another `SeatOccupant` with `isBot = true`. Phase 3 adds a server
 * coroutine that observes [GameSession.state] and submits intents on
 * the bot's behalf via [applyIntent] with a `"bot-..."` actorUserId.
 */
interface GameSessionRegistry {
    suspend fun startHand(
        code: String,
        occupants: List<SeatOccupant>,
        settings: RoomSettings,
    ): IntentResult

    suspend fun applyIntent(
        code: String,
        actorUserId: String,
        intent: PlayerIntent,
        clientNonce: String,
    ): IntentResult

    suspend fun requestNextHand(
        code: String,
        actorUserId: String,
        clientNonce: String,
    ): IntentResult

    /**
     * Synchronous lookup. Returns the active session for [code] or null
     * if the room has no game session yet. Mostly for tests and one-off
     * code paths; production subscribers use [observeSession] so they
     * pick up the session at its moment of birth.
     */
    fun peek(code: String): GameSession?

    /**
     * Reactive lookup. Emits the current session for [code] (null if
     * none) on subscribe and re-emits whenever the registry creates or
     * drops a session for that code. Used by the socket publisher to
     * `flatMapLatest` into the session's state / events streams.
     */
    fun observeSession(code: String): Flow<GameSession?>

    /**
     * Drop the session for [code]. Called when the room closes or the
     * last player leaves. Does nothing if no session exists.
     */
    suspend fun end(code: String)
}

@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class InMemoryGameSessionRegistry : GameSessionRegistry {
    // StateFlow (not ConcurrentHashMap) so subscribers can observe the
    // moment a session for their code shows up. The mutex serializes
    // the read-modify-write of the map so two concurrent startHand
    // requests don't lose a session to last-writer-wins.
    private val mutex = Mutex()
    private val sessions = MutableStateFlow<Map<String, GameSession>>(emptyMap())

    override suspend fun startHand(
        code: String,
        occupants: List<SeatOccupant>,
        settings: RoomSettings,
    ): IntentResult {
        val session = mutex.withLock {
            sessions.value[code] ?: GameSession().also { fresh ->
                sessions.value = sessions.value + (code to fresh)
            }
        }
        return session.startHand(occupants, settings)
    }

    override suspend fun applyIntent(
        code: String,
        actorUserId: String,
        intent: PlayerIntent,
        clientNonce: String,
    ): IntentResult = sessions.value[code]
        ?.applyIntent(actorUserId, intent, clientNonce)
        ?: IntentResult.Rejected("no game session for room $code")

    override suspend fun requestNextHand(
        code: String,
        actorUserId: String,
        clientNonce: String,
    ): IntentResult = sessions.value[code]
        ?.requestNextHand(actorUserId, clientNonce)
        ?: IntentResult.Rejected("no game session for room $code")

    override fun peek(code: String): GameSession? = sessions.value[code]

    override fun observeSession(code: String): Flow<GameSession?> =
        sessions.asStateFlow().map { it[code] }.distinctUntilChanged()

    override suspend fun end(code: String) {
        mutex.withLock {
            sessions.value = sessions.value - code
        }
    }
}
