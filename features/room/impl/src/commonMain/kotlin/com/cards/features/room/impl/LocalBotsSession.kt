package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.bots.BotDecision
import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.bots.BotThought
import com.dangerfield.cards.libraries.bots.OpponentTracker
import com.dangerfield.cards.libraries.bots.StreetAction
import com.dangerfield.cards.libraries.bots.buildHandContext
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameEngine
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import com.dangerfield.cards.libraries.gameplay.deterministicDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.TimeSource

class LocalBotsSession(
    private val difficulty: BotDifficulty,
    private val humanSeatIndex: Int,
    private val botPersonalities: List<BotPersonality>,
    settings: RoomSettings = RoomSettings.Default,
    private val random: Random = Random.Default,
    private val botActionDelayMs: Long = 750L,
    /**
     * Live user-chosen bot speed. Read on every loop iteration so toggling
     * it in Settings during a hand applies on the very next bot turn,
     * without restarting the session.
     */
    private val botSpeedProvider: () -> com.dangerfield.cards.libraries.cards.BotSpeed =
        { com.dangerfield.cards.libraries.cards.BotSpeed.Normal },
    /**
     * Called when a hand ends, before any next-hand setup runs. Receives the
     * event, the game state captured at hand-end (so seat contributions and
     * hole cards are still intact), and the human's stack as it was at the
     * START of this hand — used by achievement detection for "comeback"
     * style criteria that need to compare start vs. end stacks. Defaults to
     * a no-op for tests.
     */
    private val onHandEnded: (GameEvent.HandEnded, GameState, Long) -> Unit = { _, _, _ -> },
) {
    // Logger must be declared BEFORE any field whose initializer transitively
    // calls a method that logs — Kotlin runs field initializers top-to-bottom,
    // so logging from `startNextHand()` (which `gameState` invokes below)
    // sees a null `logger` if the field is further down in the file.
    private val logger = KLog.withTag("LocalBotsSession")

    private val settings: RoomSettings = settings
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<TableUiState> get() = _state

    private val tracker = OpponentTracker()
    private val personalitiesBySeat: Map<Int, BotPersonality> = buildPersonalitiesBySeat()
    private var handNumber: Int = 0
    private var buttonIndex: Int = 0
    private val lastActionBySeat: MutableMap<Int, PlayerAction> = mutableMapOf()
    private val currentStreetLog: MutableList<StreetAction> = mutableListOf()
    private var preflopAggressorSeatIndex: Int? = null
    private var lastBotThoughts: Map<Int, BotThought> = emptyMap()
    private var lastWinners: GameEvent.HandEnded? = null
    private val nextHandSignal: Channel<Unit> = Channel(capacity = 1)
    /**
     * Human's stack as it was right after blinds posted at the start of the
     * current hand. Captured once per hand from the seeded seats; readers
     * (achievement detection) need a stable "start of hand" reference even
     * after subsequent bets have moved the live seat stack.
     */
    private var humanStackAtHandStart: Long = 0L
    private var gameState: GameState = startNextHand()

    // Rolling window of recent human decision durations. Bots subtly mirror
    // the user's tempo — captured by stamping when the human becomes acting
    // and computing the delta when their intent arrives.
    private var humanTurnStartMark: TimeSource.Monotonic.ValueTimeMark? = null
    private val recentHumanPaceMs: ArrayDeque<Long> = ArrayDeque()
    private val humanPaceWindowSize: Int = 5

    private fun pushHumanPace(durationMs: Long) {
        // Clamp absurd outliers so a single distracted user (phone down for
        // 30s) doesn't poison the average for the rest of the session.
        val clamped = durationMs.coerceIn(150L, 8_000L)
        recentHumanPaceMs.addLast(clamped)
        while (recentHumanPaceMs.size > humanPaceWindowSize) recentHumanPaceMs.removeFirst()
    }

    private fun humanPaceAverageMs(): Long? {
        if (recentHumanPaceMs.isEmpty()) return null
        return recentHumanPaceMs.sum() / recentHumanPaceMs.size
    }

    private fun initialState(): TableUiState = TableUiState.Loading

    private fun startNextHand(): GameState {
        logger.d { "startNextHand: handNumber ${handNumber} -> ${handNumber + 1}" }
        handNumber += 1
        if (handNumber > 1) {
            val active = lastSeatsForRotation
                .filter { it.seatStatus == SeatStatus.Active && it.stack > 0 }
                .map { it.index }
                .sorted()
            if (active.isNotEmpty()) {
                buttonIndex = active.firstOrNull { it > buttonIndex } ?: active.first()
            }
        }
        val seedSeats = lastSeatsForRotation
        val result = GameEngine.startHand(
            settings = settings,
            seats = seedSeats,
            handNumber = handNumber,
            buttonSeatIndex = buttonIndex,
            deck = deterministicDeck(random.nextLong()),
        )
        result.events.forEach(tracker::observe)
        lastActionBySeat.clear()
        currentStreetLog.clear()
        preflopAggressorSeatIndex = null
        lastBotThoughts = emptyMap()
        lastWinners = null
        gameState = result.state
        humanStackAtHandStart = result.state.seats
            .firstOrNull { it.index == humanSeatIndex }?.stack ?: 0L
        emit()
        return result.state
    }

    private val lastSeatsForRotation: List<Seat>
        get() {
            if (handNumber == 1) {
                return List(botPersonalities.size + 1) { idx ->
                    if (idx == humanSeatIndex) {
                        Seat(
                            index = idx,
                            playerId = "human",
                            displayName = "You",
                            stack = settings.startingStack,
                            seatStatus = SeatStatus.Active,
                            handParticipation = HandParticipation.InHand,
                            isBot = false,
                        )
                    } else {
                        val botPersonality = personalitiesBySeat.getValue(idx)
                        Seat(
                            index = idx,
                            playerId = "bot-$idx",
                            displayName = botPersonality.name,
                            stack = settings.startingStack,
                            seatStatus = SeatStatus.Active,
                            handParticipation = HandParticipation.InHand,
                            isBot = true,
                        )
                    }
                }
            }
            // Practice mode: anyone who busted last hand gets a silent rebuy so the
            // table stays full and the human can't get permanently sat out.
            return gameState.seats.map { seat ->
                val needsRebuy = seat.stack <= 0
                seat.copy(
                    handParticipation = HandParticipation.NotDealt,
                    contributedThisStreet = 0,
                    contributedThisHand = 0,
                    holeCards = emptyList(),
                    hasActedThisStreet = false,
                    stack = if (needsRebuy) settings.startingStack else seat.stack,
                    seatStatus = SeatStatus.Active,
                )
            }
        }

    private fun buildPersonalitiesBySeat(): Map<Int, BotPersonality> = buildMap {
        var iter = 0
        for (i in 0 until botPersonalities.size + 1) {
            if (i == humanSeatIndex) continue
            put(i, botPersonalities[iter % botPersonalities.size])
            iter += 1
        }
    }

    suspend fun runUntilHumansTurnOrComplete() {
        logger.d {
            "runUntilHumansTurnOrComplete enter: hand=$handNumber street=${gameState.street} acting=${gameState.actingSeatIndex}"
        }
        while (gameState.actingSeatIndex != null && gameState.actingSeatIndex != humanSeatIndex) {
            val acting = gameState.actingSeatIndex!!
            val personality = personalitiesBySeat.getValue(acting)
            logger.d { "Bot loop iter: hand=$handNumber acting=$acting street=${gameState.street}" }
            // Monte Carlo equity is CPU-bound (≈200 hand evaluations per call).
            // Run off the main thread so the UI stays responsive while bots think.
            val handContext = buildHandContext(
                state = gameState,
                actingSeatIndex = acting,
                currentStreetLog = currentStreetLog.toList(),
                preflopAggressorSeatIndex = preflopAggressorSeatIndex,
            )
            val decision = withContext(Dispatchers.Default) {
                BotDecision.choose(
                    state = gameState,
                    seatIndex = acting,
                    personality = personality,
                    difficulty = difficulty,
                    opponentTracker = tracker,
                    random = random,
                    equityIterations = 200,
                    handContext = handContext,
                )
            }
            // Calibrated think delay — driven by who the bot is, how hard the
            // decision actually is, and the user's recent tempo. Computed
            // AFTER the decision so we have access to thought.handStrength /
            // potOdds for the complexity term.
            val currentSpeed = botSpeedProvider()
            val thinkDelay = BotTiming.thinkDelayMs(
                personality = personality,
                thought = decision.thought,
                userPaceMs = humanPaceAverageMs(),
                speed = currentSpeed,
            )
            delay(thinkDelay)
            // Re-check after the suspension points (`withContext`, `delay`): another
            // coroutine on Main may have advanced the state in the meantime. Without
            // this guard the engine throws "Not seat X's turn" when we try to apply
            // a stale decision. Returning to the loop top picks up the new acting
            // seat and computes a fresh decision.
            val liveActing = gameState.actingSeatIndex
            if (liveActing != acting || decision.intent.seatIndex != acting) {
                logger.w {
                    "Bot decision for seat $acting is stale " +
                        "(now acting=$liveActing, intent.seatIndex=${decision.intent.seatIndex}, " +
                        "street=${gameState.street}). Skipping apply."
                }
                continue
            }
            lastBotThoughts = lastBotThoughts + (acting to decision.thought)
            applyIntentAndEmit(decision.intent)
            // Action-tail delay also scales with user speed pref.
            delay((botActionDelayMs * currentSpeed.multiplier).toLong())
            if (gameState.street == BettingRound.Complete) break
        }

        // Stamp the moment the human becomes the actor so we can measure how
        // long they take to decide. Only set on transitions INTO the human's
        // turn (don't reset mid-think on UI re-emits).
        if (gameState.actingSeatIndex == humanSeatIndex && humanTurnStartMark == null) {
            humanTurnStartMark = TimeSource.Monotonic.markNow()
        }

        if (gameState.street == BettingRound.Complete) {
            // Drain any leftover signal so we don't auto-advance from a stale tap.
            while (nextHandSignal.tryReceive().isSuccess) Unit
            nextHandSignal.receive()
            startNextHand()
            runUntilHumansTurnOrComplete()
        } else {
            emit()
        }
    }

    fun advanceToNextHand() {
        logger.d { "advanceToNextHand: signal sent (hand=$handNumber)" }
        nextHandSignal.trySend(Unit)
    }

    suspend fun submitHumanIntent(intent: PlayerIntent) {
        logger.d {
            "submitHumanIntent: $intent hand=$handNumber street=${gameState.street} acting=${gameState.actingSeatIndex}"
        }
        if (gameState.actingSeatIndex != humanSeatIndex) {
            logger.w {
                "Intent $intent dropped — not your turn (acting=${gameState.actingSeatIndex})"
            }
            return
        }
        if (!isHumanIntentLegal(intent)) {
            val seat = gameState.seats.firstOrNull { it.index == humanSeatIndex }
            logger.w {
                "Intent $intent dropped as illegal " +
                    "(currentBet=${gameState.currentBetThisStreet}, " +
                    "contributed=${seat?.contributedThisStreet}, " +
                    "stack=${seat?.stack})"
            }
            return
        }
        logger.d { "Applying human intent $intent" }
        // Capture how long the human took before applying — `applyIntentAndEmit`
        // flips the actor so we want the duration of the just-ended turn,
        // not the new one.
        humanTurnStartMark?.let { mark ->
            pushHumanPace(mark.elapsedNow().inWholeMilliseconds)
        }
        humanTurnStartMark = null
        applyIntentAndEmit(intent)
        runUntilHumansTurnOrComplete()
    }

    private fun isHumanIntentLegal(intent: PlayerIntent): Boolean {
        if (intent.seatIndex != humanSeatIndex) return false
        val seat = gameState.seats.firstOrNull { it.index == humanSeatIndex } ?: return false
        if (!seat.canAct) return false
        val toCall = (gameState.currentBetThisStreet - seat.contributedThisStreet).coerceAtLeast(0)
        return when (intent) {
            is PlayerIntent.Fold -> true
            is PlayerIntent.Check -> toCall == 0L
            is PlayerIntent.Call -> toCall > 0L
            is PlayerIntent.Bet -> gameState.currentBetThisStreet == 0L &&
                intent.amount > 0 && intent.amount <= seat.stack
            is PlayerIntent.Raise -> gameState.currentBetThisStreet > 0L &&
                intent.totalAmountThisStreet > seat.contributedThisStreet &&
                intent.totalAmountThisStreet - seat.contributedThisStreet <= seat.stack
            is PlayerIntent.AllIn -> seat.stack > 0
        }
    }

    private fun applyIntentAndEmit(intent: PlayerIntent) {
        logger.d {
            "applyIntentAndEmit: $intent hand=$handNumber street=${gameState.street} " +
                "actingBefore=${gameState.actingSeatIndex}"
        }
        val streetBefore = gameState.street
        val result = GameEngine.applyIntent(gameState, intent)
        result.events.forEach(tracker::observe)
        gameState = result.state
        logger.d {
            "applyIntentAndEmit: applied. street=${gameState.street} " +
                "actingAfter=${gameState.actingSeatIndex} events=${result.events.size}"
        }
        result.events.forEach { ev ->
            when (ev) {
                is GameEvent.ActionTaken -> {
                    lastActionBySeat[ev.seatIndex] = ev.action
                    currentStreetLog += StreetAction(ev.seatIndex, ev.action)
                    val aggressive = ev.action is PlayerAction.Raise ||
                        ev.action is PlayerAction.Bet ||
                        ev.action is PlayerAction.AllIn
                    if (streetBefore == BettingRound.Preflop && aggressive) {
                        preflopAggressorSeatIndex = ev.seatIndex
                    }
                }
                is GameEvent.StreetAdvanced -> {
                    lastActionBySeat.clear()
                    currentStreetLog.clear()
                }
                is GameEvent.HandEnded -> {
                    lastWinners = ev
                    onHandEnded(ev, gameState, humanStackAtHandStart)
                }
                else -> Unit
            }
        }
        emit()
    }

    private fun emit() {
        _state.value = TableUiState.fromGameState(
            gameState = gameState,
            humanSeatIndex = humanSeatIndex,
            personalitiesBySeat = personalitiesBySeat,
            lastThoughts = lastBotThoughts,
            lastWinners = lastWinners,
            lastActionBySeat = lastActionBySeat.toMap(),
        )
    }

}
