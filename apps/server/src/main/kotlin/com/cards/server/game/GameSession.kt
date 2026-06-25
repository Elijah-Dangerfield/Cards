package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Deck
import com.dangerfield.cards.libraries.gameplay.GameEngine
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import com.dangerfield.cards.server.domain.HandOutcome
import com.dangerfield.cards.server.domain.PlayerHandOutcome
import com.dangerfield.cards.server.plugins.SpanAttrs
import com.dangerfield.cards.server.plugins.withSpan
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.random.Random

/**
 * Server-side wrapper around a single room's in-progress poker hand.
 *
 * Holds the authoritative [GameState] and serializes all mutations
 * behind a per-room [Mutex]. The intent path is **userId-agnostic**:
 * the same `applyIntent` accepts a human's Supabase UUID or a bot
 * driver's `"bot-..."` string. The engine validates `actorSeatIndex`
 * against the resolved seat (no spoofing — a client claiming a seat
 * it doesn't sit at is rejected).
 *
 * Exposed flows:
 *  - [state] — `StateFlow<GameState?>` so socket subscribers can
 *    re-broadcast a personalized projection on every change. Null
 *    until the first `startHand` succeeds; remains last-known across
 *    `BettingRound.Complete` so post-hand summary UIs keep rendering.
 *  - [tracedState] — the same state wrapped in a [TracedState] envelope
 *    carrying the OTel span context active at mutation time, so the
 *    socket fan-out can link each `GameStateSnapshot` send back to the
 *    causing intent. A sibling flow rather than a type change on [state]
 *    so every existing `state` reader stays untouched and tracing never
 *    leaks into the gameplay types.
 *  - [events] — `SharedFlow<TracedGameEvent>` for animation triggers,
 *    each carrying the OTel span context active at emit time so the
 *    socket fan-out can link back to the causing intent. Buffered
 *    (`replay = 16`, `extraBufferCapacity = 64`) so a subscriber that
 *    attaches a few ms after `startHand` still sees the opening
 *    `HandStarted` / `BlindPosted` / etc.
 *
 * Idempotency: every public mutation accepts a `clientNonce`. A
 * 64-entry ring buffer per session dedupes retries — re-submitting the
 * same nonce within that window returns `Accepted` with no side
 * effects. The buffer is intentionally small (idempotency is only
 * needed across single-digit retries during a network blip).
 *
 * Threading: the session is safe to call from any coroutine. All
 * public mutations suspend on the mutex; subscribers observe via the
 * cold-flow / shared-flow contracts. Don't read [state.value] outside
 * the mutex if you intend to mutate based on what you read — call the
 * session methods directly so the lock covers the read-modify-write.
 */
class GameSession internal constructor(
    private val random: Random = Random.Default,
    /**
     * Source of the deck dealt each hand. Defaults to a freshly shuffled deck;
     * the test harness injects a scripted deck (specific cards in order) to force
     * a chosen outcome — a bust, a chop, a side pot — deterministically, which a
     * shuffled deck can't. Keyed by hand number so a multi-hand scenario scripts
     * each hand independently. See MP-18.
     */
    private val deckFactory: (handNumber: Int) -> Deck = { Deck.shuffled(random) },
    /**
     * Stable identity for this session. Stamped at construction so the
     * registry's `code → session` map can stay string-keyed today while
     * the B0 `room_sessions` snapshot table keys rows by a stable UUID.
     */
    val id: UUID = UUID.randomUUID(),
    /**
     * Called inside the per-session mutex after every state mutation
     * (start hand, apply intent, request next hand, hydrate). The
     * registry wires this to the snapshot store so durable state stays
     * in step with the in-memory cache. Suspends with the mutex held —
     * the snapshot write must complete (or fail loudly) before the
     * mutation returns, so a concurrent intent can't observe out-of-
     * order durable state. Defaults to no-op for tests / unit code that
     * doesn't need persistence.
     */
    private val onStateChange: suspend (GameState) -> Unit = {},
    /**
     * Called inside the per-session mutex the moment a hand transitions
     * into [BettingRound.Complete], carrying the [HandOutcome] for the
     * just-finished hand: the **human** seats that were dealt in (bots
     * excluded) keyed to their per-hand-shape signals. The registry wires
     * this to the `HandsFinishedRepository` (finished-hand count) and
     * `ServerWitnessedAchievements` (count + per-hand achievement grants).
     * Fires exactly once per hand — once `Complete`, further intents are
     * rejected, so the transition can't re-fire. Defaults to no-op for
     * tests / unit code that doesn't need the counter.
     */
    private val onHandFinished: suspend (outcome: HandOutcome) -> Unit = { },
) {
    private val log = org.slf4j.LoggerFactory.getLogger("GameSession")

    private val mutex = Mutex()

    private val _state = MutableStateFlow<GameState?>(null)
    val state: StateFlow<GameState?> get() = _state.asStateFlow()

    private val _tracedState = MutableStateFlow<TracedState?>(null)
    val tracedState: StateFlow<TracedState?> get() = _tracedState.asStateFlow()

    private val _events = MutableSharedFlow<TracedGameEvent>(
        replay = 16,
        extraBufferCapacity = 64,
    )
    val events: SharedFlow<TracedGameEvent> get() = _events.asSharedFlow()

    /**
     * Ephemeral table-emote channel — the per-room broadcast the socket
     * fan-out collects to deliver [SeatEmoji]s to every subscriber. No
     * replay: an emote is a live, momentary reaction, so a subscriber
     * that attaches after the blast must not re-fire a stale one. Not
     * mutex-guarded — emotes carry no engine state, so [emitEmoji] is a
     * lock-free read of the current seats plus a `tryEmit`.
     */
    private val _emojiBlasts = MutableSharedFlow<SeatEmoji>(extraBufferCapacity = 32)
    val emojiBlasts: SharedFlow<SeatEmoji> get() = _emojiBlasts.asSharedFlow()

    /**
     * Match-over lifecycle channel — the heads-up grace countdown + terminal
     * resolution, driven by [MatchOverGraceDriver] and forwarded by the socket
     * fan-out. Like [emojiBlasts] it carries no engine state and isn't mutex-
     * guarded; the driver `tryEmit`s onto it. A small buffer so a GraceStarted +
     * its Resolved aren't dropped if a subscriber is momentarily slow.
     */
    private val _matchOverEvents = MutableSharedFlow<MatchOverEvent>(extraBufferCapacity = 8)
    val matchOverEvents: SharedFlow<MatchOverEvent> get() = _matchOverEvents.asSharedFlow()

    internal fun emitMatchOverEvent(event: MatchOverEvent) {
        _matchOverEvents.tryEmit(event)
    }

    // Cached so requestNextHand can re-seed without the caller re-supplying.
    private var settings: RoomSettings = RoomSettings.Default

    // Per-seat stack at the moment the current hand was dealt, keyed by
    // playerId. Captured in startHandLocked so the hand-finished outcome can
    // derive double/triple-up and bust-dealt from start-vs-end stacks. Lost
    // on a server restart mid-hand (in-memory only) — a hand that began
    // before the restart yields a 0.0 stack multiple, which the affected
    // one-shot achievements simply skip until they fire on a later hand.
    private var handStartStacks: Map<String, Long> = emptyMap()

    // Bounded ring of nonces we've already processed. New nonce → record
    // and proceed; seen nonce → swallow as idempotent Accepted. Capacity
    // is generous enough for "a few retries across a flaky moment" but
    // not infinite — we don't want to grow unbounded.
    private val processedNonces = ArrayDeque<String>()

    // Players who joined the room mid-hand and are waiting to be dealt in.
    // They watch the current hand as spectators (no seat in [_state]); the
    // next [requestNextHand] folds them into the deal and clears the queue.
    // Keyed by playerId so a re-queue (reconnect) replaces rather than
    // duplicates, and [dequeueJoiner] can drop one who left before being seated.
    private val pendingJoiners = LinkedHashMap<String, SeatOccupant>()

    // Players who left the room (a departed human, or a bot trimmed out as real
    // players arrived). Their seat may still sit in the just-finished [_state],
    // but the next [requestNextHand] must not re-deal them. Small + bounded by
    // the seats that ever existed; never cleared (gone stays gone).
    private val removedPlayerIds = mutableSetOf<String>()

    /**
     * Open a new hand. Caller supplies the current room occupants
     * (humans + bots) and the room's settings. Stacks carry over from
     * the previous hand for any occupant whose userId matched; new
     * arrivals start at [RoomSettings.startingStack]. The button rotates
     * to the next seat after the previous hand's button (or starts at
     * seat 0 on the very first hand).
     *
     * Rejected if a hand is already in progress (i.e. the current
     * state's street isn't `Complete`) or fewer than 2 occupants are
     * supplied.
     */
    suspend fun startHand(
        occupants: List<SeatOccupant>,
        settings: RoomSettings,
    ): IntentResult = mutex.withLock {
        withSpan(
            name = "start_hand",
            configure = {
                setAttribute(SpanAttrs.SessionId, id.toString())
                setAttribute(SpanAttrs.OccupantsCount, occupants.size.toLong())
            },
        ) {
            startHandLocked(occupants, settings)
        }
    }

    /**
     * Submit a player action. The actor is resolved by [actorUserId]
     * → matching `Seat.playerId`; the request is rejected if no seat
     * matches, the resolved seat isn't the current actor, or the
     * intent's `seatIndex` doesn't agree with the resolved seat (the
     * last guards against a client submitting an intent claiming
     * someone else's seat).
     *
     * Engine validation runs after the actor gate — illegal moves
     * (bet below min raise, check facing a bet, etc.) surface as a
     * `Rejected` with the engine's message.
     */
    suspend fun applyIntent(
        actorUserId: String,
        intent: PlayerIntent,
        clientNonce: String,
    ): IntentResult = mutex.withLock {
        val current = _state.value
            ?: return@withLock IntentResult.Rejected("no active hand")
        if (clientNonce in processedNonces) return@withLock IntentResult.Accepted

        // Bots and turn-timeout auto-acts route through here too; their
        // rejections are usually expected races, so log those at debug and keep
        // human rejections at info — the ones a player would file feedback about.
        val isBot = actorUserId.startsWith("bot-")
        val action = intent::class.simpleName

        // Stamp session-scoped attributes on whatever span is current —
        // this is the outer `submit_intent` span when the caller is the
        // WS route, or no-op when the SDK isn't configured. Cheap either
        // way; keeps session.id + hand.number visible at the trace root.
        Span.current().apply {
            setAttribute(SpanAttrs.SessionId, id.toString())
            setAttribute(SpanAttrs.HandNumber, current.handNumber.toLong())
        }

        val validation = withSpan(
            name = "validate_intent",
            configure = {
                setAttribute(SpanAttrs.IntentType, intent::class.simpleName ?: "Unknown")
                setAttribute(SpanAttrs.SessionId, id.toString())
                setAttribute(SpanAttrs.HandNumber, current.handNumber.toLong())
            },
        ) {
            val seat = current.seats.firstOrNull { it.playerId == actorUserId }
                ?: return@withSpan IntentValidation.Rejected("not seated in this room")
            if (current.actingSeatIndex != seat.index) {
                return@withSpan IntentValidation.Rejected("not your turn")
            }
            if (intent.seatIndex != seat.index) {
                return@withSpan IntentValidation.Rejected("intent seat does not match caller")
            }
            IntentValidation.Ok
        }
        if (validation is IntentValidation.Rejected) {
            val msg = "Intent $action rejected (validation): ${validation.reason} " +
                "— session=$id hand=${current.handNumber} actor=$actorUserId"
            if (isBot) log.debug(msg) else log.info(msg)
            return@withLock IntentResult.Rejected(validation.reason)
        }

        val resolved: EngineResolution = withSpan(
            name = "engine.apply_intent",
            configure = {
                setAttribute(SpanAttrs.IntentType, intent::class.simpleName ?: "Unknown")
                setAttribute(SpanAttrs.SessionId, id.toString())
                setAttribute(SpanAttrs.HandNumber, current.handNumber.toLong())
            },
        ) {
            try {
                EngineResolution.Resolved(GameEngine.applyIntent(current, intent))
            } catch (e: IllegalArgumentException) {
                EngineResolution.Rejected(e.message ?: "illegal intent")
            }
        }
        when (resolved) {
            is EngineResolution.Rejected -> {
                val msg = "Intent $action rejected (engine): ${resolved.reason} " +
                    "— session=$id hand=${current.handNumber} actor=$actorUserId"
                if (isBot) log.debug(msg) else log.info(msg)
                return@withLock IntentResult.Rejected(resolved.reason)
            }
            is EngineResolution.Resolved -> {
                // The state-mutate span covers the durable side-effects:
                // emit the new state, persist via `onStateChange` (which
                // hits the snapshot store — the meaningful I/O latency on
                // this path), fan events, record the nonce. Wrapping
                // these in their own span lets Tempo show "persist
                // latency" as a child of the parent submit_intent root
                // instead of folding into engine.apply_intent's wall
                // time, which would be misleading.
                withSpan(
                    name = "state_mutate",
                    configure = {
                        setAttribute(SpanAttrs.IntentType, intent::class.simpleName ?: "Unknown")
                        setAttribute(SpanAttrs.SessionId, id.toString())
                        setAttribute(SpanAttrs.HandNumber, current.handNumber.toLong())
                    },
                ) {
                    val origin = Span.current().spanContext
                    val newState = resolved.result.state
                    val handJustFinished = current.street != BettingRound.Complete &&
                        newState.street == BettingRound.Complete
                    _state.value = newState
                    _tracedState.value = TracedState(newState, origin)
                    onStateChange(newState)
                    if (handJustFinished) {
                        // Info: hand completion is a session milestone — bounds a
                        // hand in Loki and confirms settlement actually ran.
                        log.info("Hand ${current.handNumber} finished — session=$id")
                        onHandFinished(buildHandOutcome(newState, resolved.result.events))
                    }
                    resolved.result.events.forEach { _events.tryEmit(TracedGameEvent(it, origin)) }
                    recordNonce(clientNonce)
                }
                return@withLock IntentResult.Accepted
            }
        }
    }

    /**
     * Result of the validate_intent span. Lifted into a sealed type so
     * the span body can return the early-reject reason without `throw`
     * (cancellation-shaped exceptions would mark the span as ERROR; a
     * validation rejection is expected control flow, not a fault).
     */
    private sealed interface IntentValidation {
        data object Ok : IntentValidation
        data class Rejected(val reason: String) : IntentValidation
    }

    /**
     * Internal carrier for the `engine.apply_intent` span's return value
     * — Kotlin's exception-based rejection signal doesn't compose with
     * the [withSpan] return-value flow, so we lift the dichotomy into a
     * sealed type.
     */
    private sealed interface EngineResolution {
        data class Resolved(val result: com.dangerfield.cards.libraries.gameplay.StepResult) : EngineResolution
        data class Rejected(val reason: String) : EngineResolution
    }

    /**
     * Fan a table emote out to every socket in the room. Resolves the
     * caller's seat from the live state (lock-free — emotes never mutate
     * engine state) and pushes a [SeatEmoji] onto [emojiBlasts]. Rejected
     * if there's no active hand or the caller isn't seated, so a
     * spectator / stale client can't spoof an emote from someone else's
     * seat. The blast is best-effort: a full buffer drops the oldest
     * pending emote rather than blocking gameplay.
     */
    fun emitEmoji(actorUserId: String, emoji: String): IntentResult {
        val state = _state.value ?: return IntentResult.Rejected("no active hand")
        val seat = state.seats.firstOrNull { it.playerId == actorUserId }
            ?: return IntentResult.Rejected("not seated in this room")
        _emojiBlasts.tryEmit(SeatEmoji(seatIndex = seat.index, emoji = emoji))
        return IntentResult.Accepted
    }

    /**
     * Restart-time loader. Pushes a previously-persisted [GameState]
     * into the session without re-running the engine. Intended only for
     * the registry's hydration path — application code that wants a
     * mutation goes through [startHand] / [applyIntent] / [requestNextHand].
     * No `onStateChange` callback fires here: the snapshot is already
     * durable, and re-writing it the moment we read it would be
     * pointless I/O.
     */
    suspend fun hydrate(state: GameState) = mutex.withLock {
        _state.value = state
        _tracedState.value = TracedState(state, Span.current().spanContext)
    }

    /**
     * Start the next hand after the current one completes. Any seated
     * player can request it (no host gate — racing taps are idempotent
     * via the nonce buffer). Stacks carry over; occupants with zero
     * stack are dropped from the next hand (busted out).
     *
     * Rejected if the current hand isn't yet `Complete` or fewer than
     * 2 occupants have chips remaining.
     */
    suspend fun requestNextHand(
        actorUserId: String,
        clientNonce: String,
    ): IntentResult = mutex.withLock {
        val current = _state.value
            ?: return@withLock IntentResult.Rejected("no hand to continue from")
        if (current.street != BettingRound.Complete) {
            return@withLock IntentResult.Rejected("current hand not complete")
        }
        if (clientNonce in processedNonces) return@withLock IntentResult.Accepted

        val isSeated = current.seats.any { it.playerId == actorUserId }
        if (!isSeated) return@withLock IntentResult.Rejected("not seated in this room")

        val returning = current.seats
            .filter { it.playerId != null && it.stack > 0 && it.playerId !in removedPlayerIds }
            .map {
                SeatOccupant(
                    seatIndex = it.index,
                    userId = it.playerId!!,
                    displayName = it.displayName,
                    isBot = it.isBot,
                    // Preserve resolved badges + avatar + xp across hands —
                    // they're stable for the session, so we don't re-resolve
                    // each hand (level freezes per session, like badges).
                    badgeProductIds = it.badgeProductIds,
                    avatarEmoji = it.avatarEmoji,
                    avatarBackgroundColor = it.avatarBackgroundColor,
                    xp = it.xp,
                )
            }
        // Fold in anyone who joined mid-hand (skip a stray collision with a
        // returning seat, and anyone since removed), then clear the queue —
        // they're being dealt now.
        val returningIds = returning.mapTo(mutableSetOf()) { it.userId }
        val occupants = returning + pendingJoiners.values
            .filter { it.userId !in returningIds && it.userId !in removedPlayerIds }
        pendingJoiners.clear()
        if (occupants.size < 2) {
            return@withLock IntentResult.Rejected("not enough players with chips for next hand")
        }

        recordNonce(clientNonce)
        withSpan(
            name = "request_next_hand",
            configure = {
                setAttribute(SpanAttrs.SessionId, id.toString())
                setAttribute(SpanAttrs.HandNumber, current.handNumber.toLong())
            },
        ) {
            startHandLocked(occupants, settings)
        }
    }

    /**
     * Queue a player who joined the room mid-hand to be dealt in at the next
     * hand boundary. They spectate the current hand (no seat in [_state]) until
     * the next [requestNextHand] seats them. Re-queuing the same playerId
     * replaces the prior entry (e.g. a reconnect). No-op once they'd be a
     * duplicate of a live seat — [requestNextHand] guards that too.
     *
     * Clears any prior [removePlayer] mark for this id: queuing is an explicit
     * re-entry, and [removedPlayerIds] is otherwise sticky for the session — a
     * player who left mid-hand and rejoined would be filtered out of every
     * future deal forever (both the returning-seat filter and the joiner filter
     * in [requestNextHand] consult it). Re-admitting them here lets a rejoiner
     * actually be dealt back in.
     */
    suspend fun queueJoiner(occupant: SeatOccupant): Unit = mutex.withLock {
        removedPlayerIds.remove(occupant.userId)
        pendingJoiners[occupant.userId] = occupant
    }

    /** Drop a queued joiner who left before being seated, so the next deal skips them. */
    suspend fun dequeueJoiner(userId: String): Unit = mutex.withLock {
        pendingJoiners.remove(userId)
    }

    /**
     * Player ids queued to be dealt in at the next hand boundary but not yet in a
     * seat (true mid-hand joiners). The server-dealt advance gate counts these so
     * a table that drops to a single seated player with chips still deals the next
     * hand when a joiner is waiting to fill it (CARDS-24) — without this, a joiner
     * matched into a table whose only opponent then busts/trims out is stranded,
     * because [requestNextHand] is never called. Lock-free read of the current
     * keys: the driver only needs an approximate count to decide whether to
     * advance, and [requestNextHand] re-checks the real occupant count under the
     * mutex before dealing.
     */
    val pendingJoinerIds: Set<String> get() = pendingJoiners.keys.toSet()

    /**
     * Permanently drop a player from this session's future hands. Used for two
     * cases: a **human who left** the room mid-hand (so their seat isn't
     * re-dealt next hand — the long-standing "ghost seat" bug), and a **bot
     * trimmed out** as real players arrive (one bot steps aside per hand on a
     * public table, see the route-layer trim collector). Also drops any pending
     * mid-hand join under the same id, so a leave-then-rejoin race can't sneak
     * them back in. Idempotent: re-removing an already-removed id is a no-op.
     *
     * Does NOT fold the seat out of the *current* hand — call [forfeitSeat] for
     * that. This only governs who gets re-dealt at the next [requestNextHand].
     */
    suspend fun removePlayer(playerId: String): Unit = mutex.withLock {
        removedPlayerIds += playerId
        pendingJoiners.remove(playerId)
    }

    /**
     * Fold a seat out of the live hand because its player left or was reaped
     * from the room mid-hand. The seat is resolved by [actorUserId] → matching
     * `Seat.playerId`. **Idempotent and safe to call repeatedly** (the socket
     * publisher fires the leave delta once per remaining subscriber): a no-op
     * — returning [IntentResult.Accepted] — when there's no active hand, the
     * user isn't seated, or the seat is already out of the action (folded /
     * all-in / not dealt). When the forfeit ends the hand the usual
     * hand-finished side-effects fire exactly once, mirroring [applyIntent].
     *
     * Fixes the "stuck — nobody's turn" bug: a player leaving while on the
     * clock used to strand `actingSeatIndex` on a seat that would never act.
     */
    suspend fun forfeitSeat(actorUserId: String): IntentResult = mutex.withLock {
        val current = _state.value ?: return@withLock IntentResult.Accepted
        if (current.street == BettingRound.Complete) return@withLock IntentResult.Accepted
        val seat = current.seats.firstOrNull { it.playerId == actorUserId }
            ?: return@withLock IntentResult.Accepted
        if (seat.handParticipation != HandParticipation.InHand) return@withLock IntentResult.Accepted

        val step = GameEngine.forfeitSeat(current, seat.index)
        if (step.events.isEmpty()) return@withLock IntentResult.Accepted // engine treated it as a no-op

        val origin = Span.current().spanContext
        val newState = step.state
        val handJustFinished = current.street != BettingRound.Complete &&
            newState.street == BettingRound.Complete
        _state.value = newState
        _tracedState.value = TracedState(newState, origin)
        onStateChange(newState)
        if (handJustFinished) {
            log.info("Hand ${current.handNumber} finished (seat $actorUserId forfeited) — session=$id")
            onHandFinished(buildHandOutcome(newState, step.events))
        }
        step.events.forEach { _events.tryEmit(TracedGameEvent(it, origin)) }
        IntentResult.Accepted
    }

    /**
     * Buy a busted seat back into the table. Resolves the seat by
     * [actorUserId] → matching `Seat.playerId` and refills its stack to
     * [RoomSettings.startingStack] (the buy-in). Rejected unless the current
     * hand is `Complete` (we never mutate a live stack mid-action), the caller
     * is seated, and the seat is actually busted (`stack == 0`).
     *
     * **No wallet logic here** — the engine stays pure. The route debits the
     * wallet by the buy-in *before* calling this and refunds if this rejects.
     *
     * Idempotent via the nonce ring: a retried [clientNonce] returns
     * `Accepted` without re-refilling (the refill is a `set`, not an `add`, so
     * it's harmless anyway — the guard mainly mirrors the other mutations).
     *
     * Refilling to `stack > 0` is all that's needed to re-seat the player:
     * the next [requestNextHand] no longer filters them out.
     */
    suspend fun rebuy(
        actorUserId: String,
        clientNonce: String,
    ): IntentResult = mutex.withLock {
        val current = _state.value
            ?: return@withLock IntentResult.Rejected("no hand to rebuy into")
        if (current.street != BettingRound.Complete) {
            return@withLock IntentResult.Rejected("current hand not complete")
        }
        if (clientNonce in processedNonces) return@withLock IntentResult.Accepted
        val seat = current.seats.firstOrNull { it.playerId == actorUserId }
            ?: return@withLock IntentResult.Rejected("not seated in this room")
        if (seat.stack > 0) return@withLock IntentResult.Rejected("seat is not busted")

        recordNonce(clientNonce)
        withSpan(
            name = "rebuy",
            configure = {
                setAttribute(SpanAttrs.SessionId, id.toString())
                setAttribute(SpanAttrs.HandNumber, current.handNumber.toLong())
            },
        ) {
            val refilled = current.copy(
                seats = current.seats.map {
                    if (it.index == seat.index) it.copy(stack = settings.startingStack) else it
                },
            )
            val origin = Span.current().spanContext
            _state.value = refilled
            _tracedState.value = TracedState(refilled, origin)
            onStateChange(refilled)
            log.info("Seat $actorUserId rebought for ${settings.startingStack} — session=$id")
            IntentResult.Accepted
        }
    }

    private suspend fun startHandLocked(
        occupants: List<SeatOccupant>,
        settings: RoomSettings,
    ): IntentResult {
        val priorState = _state.value
        if (priorState != null && priorState.street != BettingRound.Complete) {
            return IntentResult.Rejected("hand already in progress")
        }
        if (occupants.size < 2) {
            return IntentResult.Rejected("need at least 2 occupants")
        }

        this.settings = settings
        val handNumber = (priorState?.handNumber ?: 0) + 1

        val sorted = occupants.sortedBy { it.seatIndex }
        val seats = sorted.map { occ ->
            val priorSeat = priorState?.seats?.firstOrNull { it.playerId == occ.userId }
            // Only a *revealed* bot publishes its personality to the wire. The
            // key rides every hand: occupants rebuilt for requestNextHand don't
            // carry the BotSeat, so fall back to the prior seat's key.
            val botStyleKey = occ.bot?.takeIf { it.revealed }?.personality?.name
                ?: priorSeat?.botStyleKey
            Seat(
                index = occ.seatIndex,
                playerId = occ.userId,
                displayName = occ.displayName,
                stack = priorSeat?.stack ?: settings.startingStack,
                seatStatus = SeatStatus.Active,
                handParticipation = HandParticipation.InHand,
                isBot = occ.isBot,
                badgeProductIds = occ.badgeProductIds,
                avatarEmoji = occ.avatarEmoji,
                avatarBackgroundColor = occ.avatarBackgroundColor,
                xp = occ.xp,
                botStyleKey = botStyleKey,
            )
        }

        // Button rotation. First hand starts at the lowest seat index;
        // subsequent hands rotate to the next-higher seat (wrapping).
        val sortedIndexes = seats.map { it.index }.sorted()
        val newButton = if (priorState == null) {
            sortedIndexes.first()
        } else {
            sortedIndexes.firstOrNull { it > priorState.buttonSeatIndex }
                ?: sortedIndexes.first()
        }

        val deck = deckFactory(handNumber)
        val result = GameEngine.startHand(
            settings = settings,
            seats = seats,
            handNumber = handNumber,
            buttonSeatIndex = newButton,
            deck = deck,
        )
        val origin = Span.current().spanContext
        _state.value = result.state
        _tracedState.value = TracedState(result.state, origin)
        handStartStacks = result.state.seats
            .filter { it.playerId != null }
            .associate { it.playerId!! to it.stack }
        onStateChange(result.state)
        result.events.forEach { _events.tryEmit(TracedGameEvent(it, origin)) }
        return IntentResult.Accepted
    }

    /**
     * Derive the per-hand-shape signals for the just-completed [finalState].
     * Each human seat dealt into the hand gets a [PlayerHandOutcome] built
     * from its start-of-hand stack ([handStartStacks]) versus its final
     * stack. Bots are excluded — they never carry a server-witnessed grant.
     *
     * [events] is the engine step that completed the hand; its `HandEnded`
     * carries the authoritative `byFold` per winner, which the stack delta
     * can't distinguish from a showdown win.
     */
    private fun buildHandOutcome(finalState: GameState, events: List<GameEvent>): HandOutcome {
        val potTotal = finalState.seats.sumOf { it.contributedThisHand }
        val bustedOpponentCount = finalState.seats.count { seat ->
            seat.playerId != null &&
                (handStartStacks[seat.playerId] ?: 0L) > 0L &&
                seat.stack == 0L
        }
        val foldWinnerSeats = events
            .filterIsInstance<GameEvent.HandEnded>()
            .flatMap { it.winners }
            .filter { it.byFold }
            .mapTo(mutableSetOf()) { it.seatIndex }
        val perHuman = finalState.seats
            .filter { !it.isBot && it.playerId != null }
            .associate { seat ->
                val playerId = seat.playerId!!
                val startStack = handStartStacks[playerId] ?: 0L
                val won = seat.stack > startStack
                val stackMultiple = if (startStack > 0L) seat.stack.toDouble() / startStack else 0.0
                // A winner is credited with the busts this hand; non-winners
                // can't have dealt one. A winner who also went to zero is
                // impossible (they took the pot), so excluding self is moot.
                val bustsDealt = if (won) bustedOpponentCount else 0
                playerId to PlayerHandOutcome(
                    won = won,
                    stackMultiple = stackMultiple,
                    bustsDealt = bustsDealt,
                    potTotal = potTotal,
                    wonByFold = seat.index in foldWinnerSeats,
                )
            }
        return HandOutcome(handNumber = finalState.handNumber, perHuman = perHuman)
    }

    private fun recordNonce(nonce: String) {
        if (processedNonces.size >= NONCE_RING_CAPACITY) {
            processedNonces.removeFirst()
        }
        processedNonces.addLast(nonce)
    }

    private companion object {
        const val NONCE_RING_CAPACITY = 64
    }
}

/**
 * A table emote attributed to the seat that blasted it. Rides the
 * per-room [GameSession.emojiBlasts] channel to the socket fan-out,
 * which projects it as a [com.dangerfield.cards.server.routes.RoomSocketEventDto.EmojiBlast].
 */
data class SeatEmoji(
    val seatIndex: Int,
    val emoji: String,
)
