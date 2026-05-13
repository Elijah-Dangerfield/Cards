package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.bots.BotDecision
import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.bots.BotThought
import com.dangerfield.cards.libraries.bots.OpponentTracker
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameEngine
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import com.dangerfield.cards.libraries.gameplay.deterministicDeck
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

class LocalBotsSession(
    private val difficulty: BotDifficulty,
    private val humanSeatIndex: Int,
    private val botPersonalities: List<BotPersonality>,
    settings: RoomSettings = RoomSettings.Default,
    private val random: Random = Random(0xCA5D5L),
    private val botActionDelayMs: Long = 600L,
) {
    private val settings: RoomSettings = settings
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<TableUiState> get() = _state

    private val tracker = OpponentTracker()
    private var handNumber: Int = 0
    private var buttonIndex: Int = 0
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
        _state.value = TableUiState.fromGameState(
            gameState = result.state,
            humanSeatIndex = humanSeatIndex,
            personalitiesBySeat = personalitiesBySeat,
            lastThoughts = emptyMap(),
            lastWinners = null,
        )
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
            return gameState.seats.map {
                it.copy(
                    handParticipation = HandParticipation.NotDealt,
                    contributedThisStreet = 0,
                    contributedThisHand = 0,
                    holeCards = emptyList(),
                    hasActedThisStreet = false,
                    seatStatus = if (it.stack <= 0) SeatStatus.SittingOut else it.seatStatus,
                )
            }
        }

    private val personalitiesBySeat: Map<Int, BotPersonality> by lazy {
        buildMap {
            var iter = 0
            for (i in 0 until botPersonalities.size + 1) {
                if (i == humanSeatIndex) continue
                put(i, botPersonalities[iter % botPersonalities.size])
                iter += 1
            }
        }
    }

    suspend fun runUntilHumansTurnOrComplete() {
        var lastThoughts: MutableMap<Int, BotThought> = mutableMapOf()
        while (gameState.actingSeatIndex != null && gameState.actingSeatIndex != humanSeatIndex) {
            val acting = gameState.actingSeatIndex!!
            val personality = personalitiesBySeat.getValue(acting)
            val decision = BotDecision.choose(
                state = gameState,
                seatIndex = acting,
                personality = personality,
                difficulty = difficulty,
                opponentTracker = tracker,
                random = random,
                equityIterations = 200,
            )
            lastThoughts[acting] = decision.thought
            applyIntentAndEmit(decision.intent, lastThoughts)
            delay(botActionDelayMs)
            if (gameState.street == BettingRound.Complete) break
        }

        if (gameState.street == BettingRound.Complete) {
            delay(botActionDelayMs * 2)
            startNextHand()
            runUntilHumansTurnOrComplete()
        } else {
            emit(lastThoughts.toMap())
        }
    }

    suspend fun submitHumanIntent(intent: PlayerIntent) {
        require(gameState.actingSeatIndex == humanSeatIndex) { "Not your turn" }
        applyIntentAndEmit(intent, emptyMap())
        runUntilHumansTurnOrComplete()
    }

    private fun applyIntentAndEmit(intent: PlayerIntent, thoughts: Map<Int, BotThought>) {
        val result = GameEngine.applyIntent(gameState, intent)
        result.events.forEach(tracker::observe)
        gameState = result.state
        val winners = result.events.firstNotNullOfOrNull { event ->
            (event as? GameEvent.HandEnded)
        }
        _state.value = TableUiState.fromGameState(
            gameState = gameState,
            humanSeatIndex = humanSeatIndex,
            personalitiesBySeat = personalitiesBySeat,
            lastThoughts = thoughts,
            lastWinners = winners,
        )
    }

    private fun emit(thoughts: Map<Int, BotThought>) {
        _state.value = TableUiState.fromGameState(
            gameState = gameState,
            humanSeatIndex = humanSeatIndex,
            personalitiesBySeat = personalitiesBySeat,
            lastThoughts = thoughts,
            lastWinners = null,
        )
    }
}
