package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality
import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

class PlayBotsViewModel @Inject constructor(
    @Assisted private val difficulty: BotDifficulty,
    @Assisted private val seatCount: Int,
    private val progressionRepository: ProgressionRepository,
    private val achievementRepository: AchievementRepository,
) : SEAViewModel<PlayBotsState, PlayBotsEvent, PlayBotsAction>(initialStateArg = PlayBotsState()) {

    private val logger = KLog.withTag("PlayBotsViewModel")
    private val humanSeatIndex = 0
    private val botPersonalities = BotPersonality.forDifficulty(difficulty, seatCount - 1)

    private val session = LocalBotsSession(
        difficulty = difficulty,
        humanSeatIndex = humanSeatIndex,
        botPersonalities = botPersonalities,
        onHandEnded = { event, state, humanStartingStack ->
            val summary = HandResultSummaryBuilder.build(
                event = event,
                state = state,
                humanSeatIndex = humanSeatIndex,
                mode = XpMode.BOTS,
            )
            val context = AchievementHandContext(
                opponentBotNames = state.seats
                    .filter { it.index != humanSeatIndex && it.isBot }
                    .map { it.displayName },
                botDifficulty = difficulty.name,
                humanStartingStack = humanStartingStack,
                humanEndingStack = state.seats.firstOrNull { it.index == humanSeatIndex }?.stack ?: 0L,
                bigBlind = state.settings.bigBlind,
            )
            // XP + achievements run off the engine thread — failures here
            // must not disrupt the hand-end UI flow.
            viewModelScope.launch {
                runCatching {
                    val awarded = progressionRepository.awardForHand(summary)
                    val total = awarded.sumOf { it.deltaXp }
                    if (total > 0) takeAction(PlayBotsAction.HandXpAwarded(total))
                }.onFailure { logger.w(it) { "Awarding XP failed for hand ${summary.handId}" } }
            }
            viewModelScope.launch {
                runCatching {
                    val earned = achievementRepository.recordHand(summary, context)
                    if (earned.isNotEmpty()) takeAction(PlayBotsAction.AchievementsEarned(earned))
                }.onFailure {
                    logger.w(it) { "Achievement recording failed for hand ${summary.handId}" }
                }
            }
        },
    )

    init {
        viewModelScope.launch {
            session.state.collect { uiState ->
                takeAction(PlayBotsAction.SessionStateChanged(uiState))
            }
        }
        viewModelScope.launch {
            session.runUntilHumansTurnOrComplete()
        }
        viewModelScope.launch {
            progressionRepository.observeProgression().collect { progression ->
                takeAction(PlayBotsAction.XpChanged(progression.totalXp))
            }
        }
    }

    override suspend fun handleAction(action: PlayBotsAction) {
        when (action) {
            is PlayBotsAction.SessionStateChanged -> action.updateState {
                it.copy(table = action.uiState)
            }
            is PlayBotsAction.SubmitIntent -> {
                // Don't block the SEAViewModel action channel while bots take their turns —
                // their state emissions are queued as SessionStateChanged actions and need to
                // be processed to update the UI. Run the bot loop on its own coroutine.
                viewModelScope.launch {
                    session.submitHumanIntent(action.intent)
                }
            }
            is PlayBotsAction.AdvanceNextHand -> {
                session.advanceToNextHand()
                action.updateState {
                    it.copy(lastHandXpAwarded = null, recentlyEarned = emptyList())
                }
            }
            is PlayBotsAction.ToggleCheatSheet -> action.updateState {
                it.copy(cheatSheetOpen = !it.cheatSheetOpen)
            }
            is PlayBotsAction.XpChanged -> action.updateState { it.copy(xp = action.totalXp) }
            is PlayBotsAction.HandXpAwarded -> action.updateState {
                it.copy(lastHandXpAwarded = action.amount)
            }
            is PlayBotsAction.AchievementsEarned -> action.updateState {
                it.copy(recentlyEarned = action.earned)
            }
            is PlayBotsAction.DismissEarnedToast -> action.updateState {
                it.copy(recentlyEarned = emptyList())
            }
        }
    }
}

data class PlayBotsState(
    val table: TableUiState = TableUiState.Loading,
    val cheatSheetOpen: Boolean = false,
    val xp: Long = 0,
    /** XP awarded for the most recently completed hand. Surfaced in the
     *  showdown dialog; cleared when the user advances to the next hand. */
    val lastHandXpAwarded: Int? = null,
    /** Achievements earned by the just-finished hand. Drives the unlock toast
     *  on the table screen. Cleared when the user advances or dismisses. */
    val recentlyEarned: List<EarnedAchievement> = emptyList(),
)

sealed class PlayBotsEvent {
    data object Dismissed : PlayBotsEvent()
}

sealed class PlayBotsAction {
    data class SessionStateChanged(val uiState: TableUiState) : PlayBotsAction()
    data class SubmitIntent(val intent: PlayerIntent) : PlayBotsAction()
    data object AdvanceNextHand : PlayBotsAction()
    data object ToggleCheatSheet : PlayBotsAction()
    data class XpChanged(val totalXp: Long) : PlayBotsAction()
    data class HandXpAwarded(val amount: Int) : PlayBotsAction()
    data class AchievementsEarned(val earned: List<EarnedAchievement>) : PlayBotsAction()
    data object DismissEarnedToast : PlayBotsAction()
}
