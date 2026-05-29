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
 *  - [events] — `SharedFlow<GameEvent>` for animation triggers.
 *    Buffered (`replay = 16`, `extraBufferCapacity = 64`) so a
 *    subscriber that attaches a few ms after `startHand` still sees
 *    the opening `HandStarted` / `BlindPosted` / etc.
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
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow<GameState?>(null)
    val state: StateFlow<GameState?> get() = _state.asStateFlow()

    private val _events = MutableSharedFlow<GameEvent>(
        replay = 16,
        extraBufferCapacity = 64,
    )
    val events: SharedFlow<GameEvent> get() = _events.asSharedFlow()

    // Cached so requestNextHand can re-seed without the caller re-supplying.
    private var settings: RoomSettings = RoomSettings.Default

    // Bounded ring of nonces we've already processed. New nonce → record
    // and proceed; seen nonce → swallow as idempotent Accepted. Capacity
    // is generous enough for "a few retries across a flaky moment" but
    // not infinite — we don't want to grow unbounded.
    private val processedNonces = ArrayDeque<String>()

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
            is EngineResolution.Rejected -> return@withLock IntentResult.Rejected(resolved.reason)
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
                    _state.value = resolved.result.state
                    onStateChange(resolved.result.state)
                    resolved.result.events.forEach { _events.tryEmit(it) }
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

        val occupants = current.seats
            .filter { it.playerId != null && it.stack > 0 }
            .map {
                SeatOccupant(
                    seatIndex = it.index,
                    userId = it.playerId!!,
                    displayName = it.displayName,
                    isBot = it.isBot,
                )
            }
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
            val priorStack = priorState?.seats
                ?.firstOrNull { it.playerId == occ.userId }
                ?.stack
            Seat(
                index = occ.seatIndex,
                playerId = occ.userId,
                displayName = occ.displayName,
                stack = priorStack ?: settings.startingStack,
                seatStatus = SeatStatus.Active,
                handParticipation = HandParticipation.InHand,
                isBot = occ.isBot,
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

        val deck = Deck.shuffled(random)
        val result = GameEngine.startHand(
            settings = settings,
            seats = seats,
            handNumber = handNumber,
            buttonSeatIndex = newButton,
            deck = deck,
        )
        _state.value = result.state
        onStateChange(result.state)
        result.events.forEach { _events.tryEmit(it) }
        return IntentResult.Accepted
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
