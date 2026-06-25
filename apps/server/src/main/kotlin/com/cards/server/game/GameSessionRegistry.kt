package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.HandOutcome
import com.dangerfield.cards.server.domain.HandsFinishedRepository
import com.dangerfield.cards.server.domain.NoOpHandsFinishedRepository
import com.dangerfield.cards.server.domain.NoOpRecentOpponentsRepository
import com.dangerfield.cards.server.domain.NoOpServerWitnessedAchievements
import com.dangerfield.cards.server.domain.RecentOpponentsRepository
import com.dangerfield.cards.server.domain.ServerWitnessedAchievements
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        // Open / Public tables are server-dealt: no client drives "next hand", so
        // the bot driver must auto-advance them even once all-human. Defaults
        // false so a host-run Private table keeps tapping next-hand itself.
        serverDealt: Boolean = false,
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
     * Fold [userId]'s seat out of the live hand because they left or were
     * reaped from the room mid-hand. Resolves via [peek] (no hydrate — a
     * leave only matters while a live in-memory session exists) and delegates
     * to [GameSession.forfeitSeat]. Idempotent and a no-op (Accepted) when no
     * session is registered for [code]. Keeps the table from stalling on a
     * gone player and lets the hand resolve for whoever remains.
     */
    suspend fun forfeitSeat(code: String, userId: String): IntentResult

    /**
     * Buy [userId]'s busted seat back into the table. Resolves via [peek]
     * (no hydrate — a rebuy only matters while a live in-memory session
     * exists) and delegates to [GameSession.rebuy]. Rejected when no session
     * is registered for [code]. The route owns the wallet debit; this only
     * refills the table stack.
     */
    suspend fun rebuy(code: String, userId: String, clientNonce: String): IntentResult

    /**
     * Queue a player who joined the room mid-hand to be dealt in at the next
     * hand boundary (true mid-hand join). Resolves via [peek] and delegates to
     * [GameSession.queueJoiner]; a no-op when no live session exists (the table
     * is between games, so a normal next [startHand] from the room already seats
     * them). [dequeueJoiner] drops one who left before being seated.
     */
    suspend fun queueJoiner(code: String, occupant: SeatOccupant)

    suspend fun dequeueJoiner(code: String, userId: String)

    /**
     * Permanently drop [userId] from this session's future hands. Resolves via
     * [peek] (no hydrate — only matters while a live session exists) and
     * delegates to [GameSession.removePlayer]. Used both for a human who left
     * (so their seat isn't re-dealt — the ghost-seat fix) and for a bot trimmed
     * out as real players arrive on a public table. A no-op when no session is
     * registered for [code].
     */
    suspend fun removePlayer(code: String, userId: String)

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
    private val serverWitnessedAchievements: ServerWitnessedAchievements = NoOpServerWitnessedAchievements,
    private val recentOpponentsRepository: RecentOpponentsRepository = NoOpRecentOpponentsRepository,
    // Scope the per-session bot drivers launch on. SupervisorJob so one bot's
    // failure can't tear down the others. Defaulted for direct test construction.
    private val botDriverScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    // Settle delay before a server-driven (bot or server-dealt) table deals its
    // next hand — lets a human see the result first. Defaulted to production;
    // integration tests over the real wire shrink it so a multi-hand test isn't
    // gated on real seconds per hand boundary.
    private val mixedNextHandDelayMs: Long = 4_000,
    // Fixed per-bot-turn pause override, in ms. Null keeps the production
    // humanlike timing ([serverThinkDelayMs]); a value pins every bot turn to it
    // so an integration test over the real wire isn't gated on real bot
    // think-time (which can tank to several seconds). A scalar — not the driver's
    // delay function — so callers needn't see the bots types.
    private val botThinkDelayMsOverride: Long? = null,
    // Rebuy-grace window before a heads-up bust resolves to a terminal match-over
    // (MP-14). Defaulted to production; integration tests over the real wire shrink
    // it so a bust-to-resolution test isn't gated on the real 60s.
    private val matchOverGraceMillis: Long = MatchOverGraceDriver.DEFAULT_GRACE_MILLIS,
) : GameSessionRegistry {
    // StateFlow (not ConcurrentHashMap) so subscribers can observe the
    // moment a session for their code shows up. The mutex serializes
    // the read-modify-write of the map so two concurrent startHand
    // requests don't lose a session to last-writer-wins.
    private val mutex = Mutex()
    private val sessions = MutableStateFlow<Map<String, GameSession>>(emptyMap())
    // Parallel to [sessions], keyed by code. Mutated only under [mutex] in
    // createSession / end, so it stays consistent with the session map.
    private val botDrivers = mutableMapOf<String, ServerBotDriver>()
    // Parallel to [sessions], keyed by code. Enforces the per-turn time limit
    // for human seats. Same lifecycle as [botDrivers] — spawned in createSession,
    // cancelled in end. Mutated only under [mutex].
    private val turnTimerDrivers = mutableMapOf<String, TurnTimerDriver>()
    // Parallel to [sessions], keyed by code. Resolves the heads-up dead-end (one
    // player busted, no rebuy) to a terminal match-over. Same lifecycle as the
    // other drivers — spawned in createSession, cancelled in end, mutated only
    // under [mutex].
    private val matchOverGraceDrivers = mutableMapOf<String, MatchOverGraceDriver>()

    override suspend fun startHand(
        code: String,
        occupants: List<SeatOccupant>,
        settings: RoomSettings,
        serverDealt: Boolean,
    ): IntentResult {
        val session = obtain(code)
        // Seed the driver's personality roster from this hand's occupants before
        // the hand opens, so the first bot to act already knows how to play, and
        // mark whether this table is server-dealt so it auto-advances when all-human.
        peekDriver(code)?.apply {
            updateRoster(occupants)
            setServerDealt(serverDealt)
        }
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

    override suspend fun forfeitSeat(code: String, userId: String): IntentResult =
        peek(code)?.forfeitSeat(userId) ?: IntentResult.Accepted

    override suspend fun rebuy(code: String, userId: String, clientNonce: String): IntentResult =
        peek(code)?.rebuy(userId, clientNonce)
            ?: IntentResult.Rejected("no game session for room $code")

    override suspend fun queueJoiner(code: String, occupant: SeatOccupant) {
        peek(code)?.queueJoiner(occupant)
    }

    override suspend fun dequeueJoiner(code: String, userId: String) {
        peek(code)?.dequeueJoiner(userId)
    }

    override suspend fun removePlayer(code: String, userId: String) {
        peek(code)?.removePlayer(userId)
    }

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
            botDrivers.remove(code)?.cancel()
            turnTimerDrivers.remove(code)?.cancel()
            matchOverGraceDrivers.remove(code)?.cancel()
        }
        Catching { snapshotStore.deleteByCode(code) }
            .onFailure { log.warn("Failed to delete snapshot for room {} during end()", code, it) }
    }

    private fun peekDriver(code: String): ServerBotDriver? = botDrivers[code]

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

    // Always called under [mutex] (obtain / findOrHydrate), so mutating
    // [botDrivers] here is safe. Spawns and starts the session's bot driver so
    // it's ready before the first hand opens (and re-spawns on a hydrate).
    private fun createSession(code: String, sessionId: UUID): GameSession {
        val session = GameSession(
            id = sessionId,
            onStateChange = { state -> persist(code = code, sessionId = sessionId, state = state) },
            onHandFinished = { outcome -> recordHandsFinished(sessionId = sessionId, outcome = outcome) },
        )
        botDrivers.remove(code)?.cancel()
        botDrivers[code] = ServerBotDriver(
            session = session,
            scope = botDriverScope,
            thinkDelay = botThinkDelayMsOverride
                ?.let { fixed -> { _, _, _ -> fixed } }
                ?: ::serverThinkDelayMs,
            mixedNextHandDelayMs = mixedNextHandDelayMs,
        ).also { it.start() }
        turnTimerDrivers.remove(code)?.cancel()
        turnTimerDrivers[code] = TurnTimerDriver(session = session, scope = botDriverScope).also { it.start() }
        matchOverGraceDrivers.remove(code)?.cancel()
        matchOverGraceDrivers[code] = MatchOverGraceDriver(
            session = session,
            scope = botDriverScope,
            clock = clock,
            graceMillis = matchOverGraceMillis,
        ).also { it.start() }
        return session
    }

    /**
     * Witness each human's finished-hand count, then re-evaluate the
     * server-witnessed achievements: the count-based ids off the
     * freshly-incremented count, and the per-hand-shape ids off this hand's
     * [HandOutcome]. Best-effort: a counter write or grant failing must
     * never break gameplay, so each is wrapped in [Catching]. A non-UUID
     * userId (a `"bot-..."` string slipping through, or a test fixture) is
     * skipped — only real Supabase users carry a server-witnessed count.
     * The count record runs *before* both [ServerWitnessedAchievements.evaluate]
     * and [ServerWitnessedAchievements.evaluateHand] so the count and the
     * cumulative outcome tallies (busts dealt, wins by fold) include the hand
     * just finished.
     */
    private suspend fun recordHandsFinished(sessionId: UUID, outcome: HandOutcome) {
        val handNumber = outcome.handNumber
        // Bots-only / solo table: a lone human (or none) with the rest of the
        // seats bots. Such a hand earns no MP credit — it isn't played against
        // real opponents — so it doesn't tick the server-witnessed finished-hand
        // count or its achievements ("Took a seat" and friends). Mirrors the
        // 2-human floor [recordRecentOpponents] already applies. Bots never enter
        // [HandOutcome.perHuman], so the human count is exactly its size.
        if (outcome.perHuman.size < 2) return
        for ((userIdString, playerOutcome) in outcome.perHuman) {
            val userId = Catching { UserId(UUID.fromString(userIdString)) }.getOrNull() ?: continue
            Catching {
                handsFinishedRepository.recordHandFinished(
                    userId = userId,
                    idempotencyKey = "$sessionId:$handNumber:$userIdString",
                    handSessionId = sessionId,
                    handNumber = handNumber,
                    bustsDealt = playerOutcome.bustsDealt,
                    wonByFold = playerOutcome.wonByFold,
                )
            }.onFailure { log.warn("hands_finished record failed for user {} hand {}", userIdString, handNumber, it) }
            Catching { serverWitnessedAchievements.evaluate(userId) }
                .onFailure { log.warn("server-witnessed eval failed for user {} hand {}", userIdString, handNumber, it) }
            Catching { serverWitnessedAchievements.evaluateHand(userId, playerOutcome) }
                .onFailure { log.warn("server-witnessed per-hand eval failed for user {} hand {}", userIdString, handNumber, it) }
        }
        recordRecentOpponents(handNumber, outcome.perHuman.keys)
    }

    /**
     * Record every human-vs-human pairing in this finished hand into the
     * recently-played-with shelf, both directions. [perHumanIds] are Supabase
     * user-id strings (bots are absent — they never appear in [HandOutcome.perHuman]);
     * a non-UUID string (a test fixture) is skipped. Best-effort: a write
     * failing must never break gameplay, so each is wrapped in [Catching].
     */
    private suspend fun recordRecentOpponents(handNumber: Int, perHumanIds: Set<String>) {
        val humanIds = perHumanIds.mapNotNull { Catching { UserId(UUID.fromString(it)) }.getOrNull() }
        if (humanIds.size < 2) return
        for (viewer in humanIds) {
            for (opponent in humanIds) {
                if (viewer == opponent) continue
                Catching { recentOpponentsRepository.recordPlayedTogether(viewer, opponent) }
                    .onFailure { log.warn("recently-played-with record failed for {} vs {} hand {}", viewer, opponent, handNumber, it) }
            }
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
