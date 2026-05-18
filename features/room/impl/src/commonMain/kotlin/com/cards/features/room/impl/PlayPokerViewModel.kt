package com.dangerfield.cards.features.room.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.BotSpeed
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.TurnFeedback
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.game.Personality
import com.dangerfield.cards.libraries.game.PlayStyle
import com.dangerfield.cards.libraries.game.SeatOccupant
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.Seat
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

/**
 * The new ViewModel — UI-decoupled, bot/human-agnostic, consumed via [PokerSession].
 *
 * Built next to [PlayBotsViewModel] (strangler pattern). Production routing is unchanged:
 * the screen still uses the old VM. This VM is constructible in tests directly and is
 * the target for the test suite landing in Phase 0.2.f. Once tests are green, the screen
 * is swapped (0.2.g) and the old VM deleted (0.2.h).
 *
 * Design notes:
 * - Takes a [PokerSession] supplier (not the session itself) so the lambda passed for
 *   hand-end achievement/XP wiring closes over `viewModelScope` correctly. The session
 *   is constructed lazily once the supplier is invoked in `init`.
 * - Achievement / XP / settings-mirror logic mirrors [PlayBotsViewModel] — these don't
 *   change with the session source, only the engine-state consumption does.
 * - [PlayPokerState.occupants] is derived from [GameState.seats]. Bot personalities come
 *   from a constructor-supplied map (solo) or will come from the server (MP, Phase 4).
 *
 * See `docs/architecture/game-session.md` Appendix for the locked MVI contract.
 */
class PlayPokerViewModel @Inject constructor(
    private val sessionFactory: PokerSessionFactory,
    private val progressionRepository: ProgressionRepository,
    private val achievementRepository: AchievementRepository,
    private val appCache: AppCache,
) : SEAViewModel<PlayPokerState, PlayPokerEvent, PlayPokerAction>(
    initialStateArg = PlayPokerState(),
) {

    private val logger = KLog.withTag("PlayPokerViewModel")
    private val humanSeatIndex: Int = 0

    // Cached bot-speed mirror — the session reads this via a non-suspending provider on
    // every bot turn. Same pattern as PlayBotsViewModel.
    private var latestBotSpeed: BotSpeed = BotSpeed.Normal

    // Session created lazily so the hand-end lambda below can reference `viewModelScope`.
    private val session: PokerSession = sessionFactory.create(
        humanSeatIndex = humanSeatIndex,
        botSpeedProvider = { latestBotSpeed },
        onHandEnded = { event, state, humanStartingStack ->
            handleHandEnded(event, state, humanStartingStack)
        },
    )

    init {
        // Engine state → SEA pipeline
        viewModelScope.launch {
            session.gameStateFlow.collect { gs ->
                takeAction(PlayPokerAction.GameStateUpdated(gs))
                takeAction(PlayPokerAction.OccupantsUpdated(sessionFactory.occupantsFor(gs)))
            }
        }
        // Engine events → SEA pipeline (animations, telemetry, achievement triggers)
        viewModelScope.launch {
            session.events.collect { ev ->
                takeAction(PlayPokerAction.GameEventReceived(ev))
            }
        }
        // Bootstrap the bot loop (no-op for remote sessions — they're server-driven).
        viewModelScope.launch {
            sessionFactory.bootstrap(session)
        }
        // XP mirror
        viewModelScope.launch {
            progressionRepository.observeProgression().collect { progression ->
                takeAction(PlayPokerAction.XpChanged(progression.totalXp))
            }
        }
        // Settings mirrors
        viewModelScope.launch {
            appCache.updates.collect { data ->
                takeAction(PlayPokerAction.SkipBustChanged(data.skipBustDialog))
                takeAction(PlayPokerAction.SkipLeaveConfirmChanged(data.skipLeaveBotsConfirm))
                latestBotSpeed = data.botSpeed
                takeAction(PlayPokerAction.TurnFeedbackChanged(data.turnFeedback))
            }
        }
    }

    private fun handleHandEnded(
        event: GameEvent.HandEnded,
        state: GameState,
        humanStartingStack: Long,
    ) {
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
            botDifficulty = sessionFactory.difficultyName,
            humanStartingStack = humanStartingStack,
            humanEndingStack = state.seats
                .firstOrNull { it.index == humanSeatIndex }?.stack ?: 0L,
            bigBlind = state.settings.bigBlind,
        )
        viewModelScope.launch {
            Catching {
                val awarded = progressionRepository.awardForHand(summary)
                val total = awarded.sumOf { it.deltaXp }
                if (total > 0) takeAction(PlayPokerAction.HandXpAwarded(total))
            }.onFailure { logger.w(it) { "Awarding XP failed for hand ${summary.handId}" } }

            Catching {
                val earned = achievementRepository.recordHand(summary, context)
                if (earned.isNotEmpty()) takeAction(PlayPokerAction.AchievementsEarned(earned))
            }.onFailure {
                logger.w(it) { "Achievement recording failed for hand ${summary.handId}" }
            }
        }
    }

    override suspend fun handleAction(action: PlayPokerAction) {
        when (action) {
            is PlayPokerAction.GameStateUpdated -> Unit  // occupants subaction handles state; raw state surfaced via session for future use
            is PlayPokerAction.OccupantsUpdated -> action.updateState {
                it.copy(occupants = action.occupants)
            }
            is PlayPokerAction.GameEventReceived -> Unit  // hook for haptics/sound in 0.2.f when wired

            is PlayPokerAction.Submit -> {
                logger.d { "VM received Submit ${action.intent}" }
                viewModelScope.launch { session.submit(action.intent) }
            }
            is PlayPokerAction.RequestNextHand -> {
                session.requestNextHand()
                action.updateState {
                    it.copy(lastHandXpAwarded = null, recentlyEarned = emptyList())
                }
            }

            is PlayPokerAction.ToggleCheatSheet -> action.updateState {
                it.copy(cheatSheetOpen = !it.cheatSheetOpen)
            }
            is PlayPokerAction.DismissEarnedToast -> action.updateState {
                it.copy(recentlyEarned = emptyList())
            }

            is PlayPokerAction.SetSkipBustDialog ->
                appCache.update { it.copy(skipBustDialog = action.value) }
            is PlayPokerAction.SetSkipLeaveConfirm ->
                appCache.update { it.copy(skipLeaveBotsConfirm = action.value) }

            is PlayPokerAction.XpChanged -> action.updateState { it.copy(xp = action.totalXp) }
            is PlayPokerAction.SkipBustChanged -> action.updateState {
                it.copy(skipBustDialog = action.value)
            }
            is PlayPokerAction.SkipLeaveConfirmChanged -> action.updateState {
                it.copy(skipLeaveBotsConfirm = action.value)
            }
            is PlayPokerAction.TurnFeedbackChanged -> action.updateState {
                it.copy(turnFeedback = action.value)
            }

            is PlayPokerAction.HandXpAwarded -> action.updateState {
                it.copy(lastHandXpAwarded = action.amount)
            }
            is PlayPokerAction.AchievementsEarned -> action.updateState {
                it.copy(recentlyEarned = action.earned)
            }
        }
    }
}

// ---------- MVI types ----------

data class PlayPokerState(
    val occupants: List<SeatOccupant> = emptyList(),
    val cheatSheetOpen: Boolean = false,
    val xp: Long = 0,
    val lastHandXpAwarded: Int? = null,
    val recentlyEarned: List<EarnedAchievement> = emptyList(),
    val skipBustDialog: Boolean = false,
    val skipLeaveBotsConfirm: Boolean = false,
    val turnFeedback: TurnFeedback = TurnFeedback.Sound,
    val connection: ConnectionState = ConnectionState.Connected,
)

sealed interface PlayPokerAction {
    // Engine subscriptions (internal — fired by VM's own session observers)
    data class GameStateUpdated(val state: GameState) : PlayPokerAction
    data class GameEventReceived(val event: GameEvent) : PlayPokerAction
    data class OccupantsUpdated(val occupants: List<SeatOccupant>) : PlayPokerAction

    // Player intents (from UI taps)
    data class Submit(val intent: PlayerIntent) : PlayPokerAction
    data object RequestNextHand : PlayPokerAction

    // Local UI
    data object ToggleCheatSheet : PlayPokerAction
    data object DismissEarnedToast : PlayPokerAction

    // Settings setters (UI → cache)
    data class SetSkipBustDialog(val value: Boolean) : PlayPokerAction
    data class SetSkipLeaveConfirm(val value: Boolean) : PlayPokerAction

    // Settings mirrors (cache flow → state)
    data class XpChanged(val totalXp: Long) : PlayPokerAction
    data class SkipBustChanged(val value: Boolean) : PlayPokerAction
    data class SkipLeaveConfirmChanged(val value: Boolean) : PlayPokerAction
    data class TurnFeedbackChanged(val value: TurnFeedback) : PlayPokerAction

    // Hand-end transients (internal — fired by hand-end callback)
    data class HandXpAwarded(val amount: Int) : PlayPokerAction
    data class AchievementsEarned(val earned: List<EarnedAchievement>) : PlayPokerAction
}

sealed interface PlayPokerEvent {
    data object NavigatedBack : PlayPokerEvent
    data class ShowAchievementUnlock(val achievement: EarnedAchievement) : PlayPokerEvent
    data class PlayHaptic(val kind: HapticKind) : PlayPokerEvent
    data class PlaySound(val kind: SoundKind) : PlayPokerEvent
}

enum class HapticKind { ActionTaken, HandWon, HandLost, Bust, LevelUp, AchievementUnlock }
enum class SoundKind { CardFlick, ChipClick, Showdown, AchievementChime }

/**
 * Factory the VM depends on for session creation + occupant derivation. Decouples the
 * VM from concrete session construction (which needs bot-personality + difficulty params
 * for solo, room-code params for MP, etc.) and from the bot loop bootstrap.
 *
 * In production wiring (Phase 0.2.g), a `SoloBotsSessionFactory` implementation builds
 * a [LocalBotsSession]. In tests, a fake produces a fake [PokerSession] and a no-op
 * bootstrap.
 */
interface PokerSessionFactory {
    val difficultyName: String

    fun create(
        humanSeatIndex: Int,
        botSpeedProvider: () -> BotSpeed,
        onHandEnded: (GameEvent.HandEnded, GameState, Long) -> Unit,
    ): PokerSession

    /**
     * Start the session's run loop. For local-bots sessions, calls
     * `runUntilHumansTurnOrComplete`. For remote sessions (Phase 4), connects the
     * WebSocket. Suspends until the session is torn down.
     */
    suspend fun bootstrap(session: PokerSession)

    /** Derive [SeatOccupant] list from current engine state. */
    fun occupantsFor(state: GameState): List<SeatOccupant>
}

/**
 * Helper used by [PokerSessionFactory] implementations and tests. Maps engine [Seat] to
 * [SeatOccupant] given an optional personality map (solo mode supplies it; MP mode will
 * source from server-provided occupant metadata).
 */
internal fun seatToOccupant(
    seat: Seat,
    personality: Personality?,
): SeatOccupant = when {
    seat.playerId == null -> SeatOccupant.Empty(seatIndex = seat.index)
    seat.isBot -> SeatOccupant.Bot(
        seatIndex = seat.index,
        displayName = seat.displayName,
        personality = personality ?: Personality(label = seat.displayName, style = PlayStyle.Unknown),
    )
    else -> SeatOccupant.Human(
        seatIndex = seat.index,
        displayName = seat.displayName,
        userId = seat.playerId ?: "",
        personality = personality,
        level = 0,             // sourced from progression repo in a later chunk
        leagueTier = null,     // sourced from league repo (V1.1)
    )
}
