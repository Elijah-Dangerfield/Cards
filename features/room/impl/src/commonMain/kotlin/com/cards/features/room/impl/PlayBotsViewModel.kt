package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality
import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.core.Catching
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
    private val appCache: AppCache,
) : SEAViewModel<PlayBotsState, PlayBotsEvent, PlayBotsAction>(initialStateArg = PlayBotsState()) {

    private val logger = KLog.withTag("PlayBotsViewModel")
    private val humanSeatIndex = 0
    private val botPersonalities = BotPersonality.forDifficulty(difficulty, seatCount - 1)
    // Cached so the LocalBotsSession lambda has a non-suspending read path.
    // Updated as appCache flow emits. Eventual consistency across coroutines
    // is fine — the session reads it once per bot turn.
    private var latestBotSpeed: com.dangerfield.cards.libraries.cards.BotSpeed =
        com.dangerfield.cards.libraries.cards.BotSpeed.Normal

    private val session = LocalBotsSession(
        difficulty = difficulty,
        humanSeatIndex = humanSeatIndex,
        botPersonalities = botPersonalities,
        // Read the live preference on every bot turn so a setting toggle
        // mid-hand takes effect immediately on the next bot decision.
        botSpeedProvider = { latestBotSpeed },
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
                Catching {
                    val awarded = progressionRepository.awardForHand(summary)
                    val total = awarded.sumOf { it.deltaXp }
                    if (total > 0) takeAction(PlayBotsAction.HandXpAwarded(total))
                }.onFailure { logger.w(it) { "Awarding XP failed for hand ${summary.handId}" } }

                // Sequenced after XP awarding: the achievement engine reads the
                // current total XP to mirror the player's level into a counter,
                // so level-threshold criteria evaluate against this hand's XP.
                Catching {
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
        viewModelScope.launch {
            appCache.updates.collect { data ->
                takeAction(PlayBotsAction.SkipBustChanged(data.skipBustDialog))
                takeAction(PlayBotsAction.SkipLeaveConfirmChanged(data.skipLeaveBotsConfirm))
                latestBotSpeed = data.botSpeed
                takeAction(PlayBotsAction.TurnFeedbackChanged(data.turnFeedback))
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
                logger.d { "VM received SubmitIntent ${action.intent}" }
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
            is PlayBotsAction.SkipBustChanged -> action.updateState {
                it.copy(skipBustDialog = action.value)
            }
            is PlayBotsAction.SetSkipBustDialog -> {
                appCache.update { it.copy(skipBustDialog = action.value) }
            }
            is PlayBotsAction.SkipLeaveConfirmChanged -> action.updateState {
                it.copy(skipLeaveBotsConfirm = action.value)
            }
            is PlayBotsAction.SetSkipLeaveConfirm -> {
                appCache.update { it.copy(skipLeaveBotsConfirm = action.value) }
            }
            is PlayBotsAction.TurnFeedbackChanged -> action.updateState {
                it.copy(turnFeedback = action.value)
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
    /** Mirror of `AppData.skipBustDialog`. When true, the bust modal is
     *  bypassed and the user goes straight into the next hand. */
    val skipBustDialog: Boolean = false,
    /** Mirror of `AppData.skipLeaveBotsConfirm`. When true, back press
     *  exits the session immediately without a confirmation modal. */
    val skipLeaveBotsConfirm: Boolean = false,
    /** Mirror of `AppData.turnFeedback`. Drives the haptic / audio cue when
     *  the human becomes the actor. */
    val turnFeedback: com.dangerfield.cards.libraries.cards.TurnFeedback =
        com.dangerfield.cards.libraries.cards.TurnFeedback.Sound,
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
    /** Cache flow → state mirror. */
    data class SkipBustChanged(val value: Boolean) : PlayBotsAction()
    /** User toggled the "Don't show me this again" checkbox. */
    data class SetSkipBustDialog(val value: Boolean) : PlayBotsAction()
    data class SkipLeaveConfirmChanged(val value: Boolean) : PlayBotsAction()
    data class SetSkipLeaveConfirm(val value: Boolean) : PlayBotsAction()
    data class TurnFeedbackChanged(
        val value: com.dangerfield.cards.libraries.cards.TurnFeedback,
    ) : PlayBotsAction()
}
