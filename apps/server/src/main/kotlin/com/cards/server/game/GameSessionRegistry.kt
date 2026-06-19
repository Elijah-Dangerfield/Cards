package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.HandsFinishedRepository
import com.dangerfield.cards.server.domain.NoOpHandsFinishedRepository
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import org.slf4j.LoggerFactory
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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
 * Persistence: every state mutation inside a session writes through to
 * [SessionSnapshotStore] (the B0 `room_sessions` table). On a request
 * for a code that isn't in memory, the registry first asks the store
 * whether a snapshot exists and hydrates a fresh session from it — that
 * is the server-restart-mid-hand recovery path. Tests that don't want
 * Postgres in the loop inject [NoOpSessionSnapshotStore].
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
     * Fan a table emote out to every socket in the room. Resolves to the
     * in-memory session via [peek] (no hydrate — emotes only matter while
     * a hand is live and someone's watching the table) and delegates to
     * [GameSession.emitEmoji]. Rejected when no session is registered for
     * [code].
     */
    fun broadcastEmoji(code: String, actorUserId: String, emoji: String): IntentResult

    /**
     * Synchronous lookup of an in-memory session. Returns null when the
     * registry doesn't currently hold one for [code] — does **not**
     * consult the snapshot store. Mostly for tests and one-off code
     * paths; production subscribers use [observeSession] so they pick
     * up the session at its moment of birth and use [findOrHydrate] when
     * they explicitly need the post-restart recovery path.
     */
    fun peek(code: String): GameSession?

    /**
     * Suspending lookup that also hydrates from the snapshot store on
     * miss. Use this when callers (HTTP routes, WS connection setup)
     * need to discover whether a durable session exists for the code,
     * even after a server restart.
     */
    suspend fun findOrHydrate(code: String): GameSession?

    /**
     * Reactive lookup. Emits the current session for [code] (null if
     * none) on subscribe and re-emits whenever the registry creates or
     * drops a session for that code. Used by the socket publisher to
     * `flatMapLatest` into the session's state / events streams.
     */
    fun observeSession(code: String): Flow<GameSession?>

    /**
     * Drop the session for [code]. Called when the room closes or the
     * last player leaves. Removes the durable snapshot too so a future
     * lookup doesn't resurrect the dead session.
     */
    suspend fun end(code: String)
}

@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class DefaultGameSessionRegistry(
    private val snapshotStore: SessionSnapshotStore,
    private val clock: Clock,
    // Last + defaulted so existing positional constructions stay valid. DI
    // resolves the real binding here; the default only serves direct
    // construction in tests that don't care about the counter.
    private val handsFinishedRepository: HandsFinishedRepository = NoOpHandsFinishedRepository,
) : GameSessionRegistry {
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
        val session = obtain(code)
        return session.startHand(occupants, settings)
    }

    override suspend fun applyIntent(
        code: String,
        actorUserId: String,
        intent: PlayerIntent,
        clientNonce: String,
    ): IntentResult = findOrHydrate(code)
        ?.applyIntent(actorUserId, intent, clientNonce)
        ?: IntentResult.Rejected("no game session for room $code")

    override suspend fun requestNextHand(
        code: String,
        actorUserId: String,
        clientNonce: String,
    ): IntentResult = findOrHydrate(code)
        ?.requestNextHand(actorUserId, clientNonce)
        ?: IntentResult.Rejected("no game session for room $code")

    override fun broadcastEmoji(code: String, actorUserId: String, emoji: String): IntentResult =
        peek(code)?.emitEmoji(actorUserId, emoji)
            ?: IntentResult.Rejected("no game session for room $code")

    override fun peek(code: String): GameSession? = sessions.value[code]

    override suspend fun findOrHydrate(code: String): GameSession? {
        sessions.value[code]?.let { return it }
        val snapshot = snapshotStore.readByCode(code) ?: return null
        return mutex.withLock {
            sessions.value[code]?.let { return@withLock it }
            val hydrated = createSession(code = code, sessionId = snapshot.sessionId)
            hydrated.hydrate(snapshot.state)
            sessions.value = sessions.value + (code to hydrated)
            hydrated
        }
    }

    override fun observeSession(code: String): Flow<GameSession?> =
        sessions.asStateFlow().map { it[code] }.distinctUntilChanged()

    override suspend fun end(code: String) {
        mutex.withLock {
            sessions.value = sessions.value - code
        }
        Catching { snapshotStore.deleteByCode(code) }
            .onFailure { log.warn("Failed to delete snapshot for room {} during end()", code, it) }
    }

    private suspend fun obtain(code: String): GameSession {
        sessions.value[code]?.let { return it }
        val snapshot = snapshotStore.readByCode(code)
        return mutex.withLock {
            sessions.value[code] ?: run {
                val fresh = if (snapshot != null) {
                    createSession(code = code, sessionId = snapshot.sessionId)
                        .also { it.hydrate(snapshot.state) }
                } else {
                    createSession(code = code, sessionId = UUID.randomUUID())
                }
                sessions.value = sessions.value + (code to fresh)
                fresh
            }
        }
    }

    private fun createSession(code: String, sessionId: UUID): GameSession = GameSession(
        id = sessionId,
        onStateChange = { state -> persist(code = code, sessionId = sessionId, state = state) },
        onHandFinished = { humanUserIds, handNumber ->
            recordHandsFinished(sessionId = sessionId, handNumber = handNumber, humanUserIds = humanUserIds)
        },
    )

    /**
     * Witness each human's finished-hand count. Best-effort: a counter
     * write failing must never break gameplay, so each is wrapped in
     * [Catching]. A non-UUID actorUserId (a `"bot-..."` string slipping
     * through, or a test fixture) is skipped — only real Supabase users
     * carry a server-witnessed count.
     */
    private suspend fun recordHandsFinished(sessionId: UUID, handNumber: Int, humanUserIds: List<String>) {
        for (userIdString in humanUserIds) {
            val userId = Catching { UserId(UUID.fromString(userIdString)) }.getOrNull() ?: continue
            Catching {
                handsFinishedRepository.recordHandFinished(
                    userId = userId,
                    idempotencyKey = "$sessionId:$handNumber:$userIdString",
                    handSessionId = sessionId,
                    handNumber = handNumber,
                )
            }.onFailure { log.warn("hands_finished record failed for user {} hand {}", userIdString, handNumber, it) }
        }
    }

    private suspend fun persist(code: String, sessionId: UUID, state: GameState) {
        Catching {
            snapshotStore.upsert(
                SessionSnapshot(
                    sessionId = sessionId,
                    code = code,
                    state = state,
                    updatedAt = clock.now(),
                ),
            )
        }.onFailure { log.warn("Snapshot persist failed for room {} session {}", code, sessionId, it) }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(DefaultGameSessionRegistry::class.java)
    }
}
