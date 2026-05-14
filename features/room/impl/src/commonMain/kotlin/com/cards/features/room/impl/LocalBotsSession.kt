package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.bots.BotDecision
import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.bots.BotThought
import com.dangerfield.cards.libraries.bots.OpponentTracker
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

class LocalBotsSession(
    private val difficulty: BotDifficulty,
    private val humanSeatIndex: Int,
    private val botPersonalities: List<BotPersonality>,
    settings: RoomSettings = RoomSettings.Default,
    private val random: Random = Random.Default,
    private val botActionDelayMs: Long = 750L,
    /**
     * Called when a hand ends, before any next-hand setup runs. Receives the
     * event and the game state captured at hand-end (so seat contributions
     * and hole cards are still intact). Defaults to a no-op for tests.
     */
    private val onHandEnded: (GameEvent.HandEnded, GameState) -> Unit = { _, _ -> },
) {
    private val settings: RoomSettings = settings
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<TableUiState> get() = _state

    private val tracker = OpponentTracker()
    private val personalitiesBySeat: Map<Int, BotPersonality> = buildPersonalitiesBySeat()
    private var handNumber: Int = 0
    private var buttonIndex: Int = 0
    private val lastActionBySeat: MutableMap<Int, PlayerAction> = mutableMapOf()
    private var lastBotThoughts: Map<Int, BotThought> = emptyMap()
    private var lastWinners: GameEvent.HandEnded? = null
    private val nextHandSignal: Channel<Unit> = Channel(capacity = 1)
    private var gameState: GameState = startNextHand()

    private fun initialState(): TableUiState = TableUiState.Loading

    private fun startNextHand(): GameState {
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
        lastBotThoughts = emptyMap()
        lastWinners = null
        gameState = result.state
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
        while (gameState.actingSeatIndex != null && gameState.actingSeatIndex != humanSeatIndex) {
            val acting = gameState.actingSeatIndex!!
            val personality = personalitiesBySeat.getValue(acting)
            delay(botThinkingDelayMs)
            // Monte Carlo equity is CPU-bound (≈200 hand evaluations per call).
            // Run off the main thread so the UI stays responsive while bots think.
            val decision = withContext(Dispatchers.Default) {
                BotDecision.choose(
                    state = gameState,
                    seatIndex = acting,
                    personality = personality,
                    difficulty = difficulty,
                    opponentTracker = tracker,
                    random = random,
                    equityIterations = 200,
                )
            }
            lastBotThoughts = lastBotThoughts + (acting to decision.thought)
            applyIntentAndEmit(decision.intent)
            delay(botActionDelayMs)
            if (gameState.street == BettingRound.Complete) break
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
        nextHandSignal.trySend(Unit)
    }

    suspend fun submitHumanIntent(intent: PlayerIntent) {
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
        applyIntentAndEmit(intent)
        runUntilHumansTurnOrComplete()
    }

    private val logger = KLog.withTag("LocalBotsSession")

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
        val result = GameEngine.applyIntent(gameState, intent)
        result.events.forEach(tracker::observe)
        gameState = result.state
        result.events.forEach { ev ->
            when (ev) {
                is GameEvent.ActionTaken -> lastActionBySeat[ev.seatIndex] = ev.action
                is GameEvent.StreetAdvanced -> lastActionBySeat.clear()
                is GameEvent.HandEnded -> {
                    lastWinners = ev
                    onHandEnded(ev, gameState)
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

    private val botThinkingDelayMs: Long = (botActionDelayMs * 2) / 3
}
