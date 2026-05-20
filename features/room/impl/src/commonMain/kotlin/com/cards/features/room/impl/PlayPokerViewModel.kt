package com.dangerfield.cards.features.room.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.bots.HandStrength
import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.BotSpeed
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.TurnFeedback
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.game.Personality
import com.dangerfield.cards.libraries.game.PlayStyle
import com.dangerfield.cards.libraries.game.SeatOccupant
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.identity.Identity
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.IdentityState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

/**
 * The bot/human-agnostic ViewModel that backs the play-poker screen.
 *
 * Consumes [PokerSession] (UI-decoupled engine state + events) via a
 * [PokerSessionFactory] injected by the [PlayPokerFeatureEntryPoint]. For
 * solo mode the factory is [SoloBotsPokerSessionFactory]; for MP (Phase 4)
 * it will be a `RemotePokerSessionFactory` satisfying the same interface
 * with no VM changes required.
 *
 * Design notes:
 * - Takes a session FACTORY (not a session) so the hand-end lambda below
 *   can close over `viewModelScope` correctly — the session is constructed
 *   in the init block.
 * - [PlayPokerState.table] is projected from raw [GameState] via the
 *   factory's `tableFor`; per-hand transients (winners, last-action pills)
 *   come from engine events the VM observes.
 */
@OptIn(ExperimentalCoroutinesApi::class) // mapLatest — needed for cancel-in-flight equity math
class PlayPokerViewModel @Inject constructor(
    @Assisted private val sessionFactory: PokerSessionFactory,
    private val progressionRepository: ProgressionRepository,
    private val achievementRepository: AchievementRepository,
    private val appCache: AppCache,
    private val equipmentRepository: EquipmentRepository,
    private val identityRepository: IdentityRepository,
    private val dispatcherProvider: DispatcherProvider,
) : SEAViewModel<PlayPokerState, PlayPokerEvent, PlayPokerAction>(
    initialStateArg = PlayPokerState(),
) {

    private val logger = KLog.withTag("PlayPokerViewModel")
    private val humanSeatIndex: Int = 0

    // Cached bot-speed mirror — the session reads this via a non-suspending
    // provider on every bot turn so a settings toggle mid-hand takes effect
    // on the next bot decision.
    private var latestBotSpeed: BotSpeed = BotSpeed.Normal

    // Per-hand transient state that feeds into TableUiState projection but
    // ISN'T part of [GameState]. We track them from engine events and pass
    // into the factory's projection on every state emission. Cleared at the
    // start of each hand.
    private var lastWinners: GameEvent.HandEnded? = null
    private val lastActionBySeat: MutableMap<Int, PlayerAction> = mutableMapOf()

    // Latest known identity. Captured here so the [tableFor] projection can
    // render the human seat with the user's chosen display name + avatar
    // emoji instead of the engine-side "You" / null placeholders. Null
    // until the first SignedIn emission lands; re-projects when it does.
    private var latestHumanIdentity: Identity? = null
    private var lastGameState: GameState? = null

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
        // Connection health → state. Local sessions stay pinned to
        // [ConnectionState.Connected]; remote sessions transition as the
        // socket lifecycle dictates. The screen renders a banner whenever
        // this isn't [Connected].
        viewModelScope.launch {
            session.connectionState.collect { conn ->
                takeAction(PlayPokerAction.ConnectionChanged(conn))
            }
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
                latestBotSpeed = data.botSpeed
                takeAction(PlayPokerAction.TurnFeedbackChanged(data.turnFeedback))
            }
        }
        // Identity → re-project the table so the human seat picks up the
        // user's chosen display name + avatar emoji.
        viewModelScope.launch {
            identityRepository.state.collect { st ->
                val id = (st as? IdentityState.SignedIn)?.identity ?: return@collect
                latestHumanIdentity = id
                lastGameState?.let { takeAction(PlayPokerAction.GameStateUpdated(it)) }
            }
        }
        // Equipped felt + card back — observed so mid-session toggles
        // from the My Items screen repaint without re-entry. Single
        // subscription, two derived values: the flow yields newest-equip-
        // first, so we pick the first non-Default per slot. Also surfaces
        // the boolean for the win-odds tool — the live-equity feature
        // gates on it.
        viewModelScope.launch {
            equipmentRepository.observeEquipped().collect { entries ->
                val felt = entries
                    .map { feltForProductId(it.productId) }
                    .firstOrNull { it != EquippedFelt.Default }
                    ?: EquippedFelt.Default
                val cardBack = entries
                    .map { cardBackForProductId(it.productId) }
                    .firstOrNull { it != com.dangerfield.cards.libraries.ui.components.poker.CardBackStyle.Default }
                    ?: com.dangerfield.cards.libraries.ui.components.poker.CardBackStyle.Default
                val winOddsTool = entries.any { it.productId == TOOL_WIN_ODDS_PRODUCT_ID }
                // Newest-equipped-title wins so a freshly-equipped title
                // takes over from a prior one without an explicit unequip.
                val title = entries.firstNotNullOfOrNull { titleForProductId(it.productId) }
                takeAction(PlayPokerAction.EquippedFeltChanged(felt))
                takeAction(PlayPokerAction.EquippedCardBackChanged(cardBack))
                takeAction(PlayPokerAction.WinOddsToolEquippedChanged(winOddsTool))
                takeAction(PlayPokerAction.EquippedTitleChanged(title))
            }
        }
        // Live win-odds — only computes when the user owns + equips the
        // tool, only on inputs that actually matter for equity (their
        // hole cards, the visible board, the count of opponents still
        // in the hand). distinctUntilChanged + mapLatest means we cancel
        // in-flight Monte Carlo runs the moment any input shifts (e.g.
        // a fold reduces opponent count mid-river).
        viewModelScope.launch {
            combine(
                session.gameStateFlow,
                stateFlow,
            ) { gs, vmState ->
                if (!vmState.winOddsToolEquipped) {
                    EquityInput.NotApplicable
                } else {
                    val human = gs.seats.firstOrNull { it.index == humanSeatIndex }
                        ?: return@combine EquityInput.NotApplicable
                    if (human.holeCards.size != 2) return@combine EquityInput.NotApplicable
                    val opponentsInHand = gs.seats.count { seat ->
                        seat.index != humanSeatIndex &&
                            (seat.handParticipation == HandParticipation.InHand ||
                                seat.handParticipation == HandParticipation.AllIn)
                    }
                    if (opponentsInHand == 0) return@combine EquityInput.NotApplicable
                    EquityInput.Compute(
                        hole = human.holeCards,
                        community = gs.community,
                        opponents = opponentsInHand,
                    )
                }
            }
                .distinctUntilChanged()
                .onEach { input ->
                    if (input is EquityInput.NotApplicable) {
                        takeAction(PlayPokerAction.WinPercentChanged(null))
                    }
                }
                .mapLatest { input ->
                    if (input is EquityInput.Compute) {
                        val equity = withContext(dispatcherProvider.default) {
                            HandStrength.equityVsRandom(
                                holeCards = input.hole,
                                community = input.community,
                                numOpponents = input.opponents,
                                iterations = WIN_ODDS_ITERATIONS,
                            )
                        }
                        (equity * 100).toInt().coerceIn(0, 100)
                    } else null
                }
                .collect { winPercent ->
                    if (winPercent != null) {
                        takeAction(PlayPokerAction.WinPercentChanged(winPercent))
                    }
                }
        }
    }

    private sealed interface EquityInput {
        data object NotApplicable : EquityInput
        data class Compute(
            val hole: List<Card>,
            val community: List<Card>,
            val opponents: Int,
        ) : EquityInput
    }

    private companion object {
        const val TOOL_WIN_ODDS_PRODUCT_ID = "tool_win_odds"
        /**
         * 400 Monte Carlo iterations balances accuracy with phone CPU
         * — empirically converges to within ~1% of the true equity by
         * 400 trials in heads-up scenarios, drifts to ~2% in 5-handed
         * pots. The UI rounds to whole percents anyway. Bumping this
         * higher makes ticks expensive (~ms scales linearly).
         */
        const val WIN_ODDS_ITERATIONS = 400
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
            // Opponents whose stack hit zero this hand. Bots auto-rebuy at
            // the start of the *next* hand, so the end-of-hand snapshot
            // still shows their bust at attribution time.
            bustedOpponentCount = state.seats
                .count { it.index != humanSeatIndex && it.stack <= 0 },
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
            is PlayPokerAction.GameStateUpdated -> {
                lastGameState = action.state
                action.updateState {
                    it.copy(
                        table = sessionFactory.tableFor(
                            state = action.state,
                            lastWinners = lastWinners,
                            lastActionBySeat = lastActionBySeat.toMap(),
                            humanIdentity = latestHumanIdentity,
                        ),
                    )
                }
            }
            is PlayPokerAction.OccupantsUpdated -> action.updateState {
                it.copy(occupants = action.occupants)
            }
            is PlayPokerAction.GameEventReceived -> {
                // Track transients that the TableUiState projection needs but
                // GameState alone can't carry — most notably the HandEnded
                // event (used for showdown rendering) and the most recent
                // action per seat (rendered as a "Folded" / "Called X" pill).
                when (val ev = action.event) {
                    is GameEvent.HandStarted -> {
                        lastWinners = null
                        lastActionBySeat.clear()
                    }
                    is GameEvent.StreetAdvanced -> lastActionBySeat.clear()
                    is GameEvent.ActionTaken -> lastActionBySeat[ev.seatIndex] = ev.action
                    is GameEvent.HandEnded -> lastWinners = ev
                    else -> Unit
                }
            }

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

            is PlayPokerAction.XpChanged -> action.updateState { it.copy(xp = action.totalXp) }
            is PlayPokerAction.TurnFeedbackChanged -> action.updateState {
                it.copy(turnFeedback = action.value)
            }

            is PlayPokerAction.HandXpAwarded -> action.updateState {
                it.copy(lastHandXpAwarded = action.amount)
            }
            is PlayPokerAction.AchievementsEarned -> action.updateState {
                it.copy(recentlyEarned = action.earned)
            }
            is PlayPokerAction.EquippedFeltChanged -> action.updateState {
                it.copy(equippedFelt = action.felt)
            }
            is PlayPokerAction.EquippedCardBackChanged -> action.updateState {
                it.copy(equippedCardBack = action.style)
            }
            is PlayPokerAction.WinOddsToolEquippedChanged -> action.updateState {
                // Clear any stale percent when the tool flips off — UI
                // hides the badge on the next compose pass.
                it.copy(
                    winOddsToolEquipped = action.equipped,
                    humanWinPercent = if (action.equipped) it.humanWinPercent else null,
                )
            }
            is PlayPokerAction.WinPercentChanged -> action.updateState {
                it.copy(humanWinPercent = action.percent)
            }
            is PlayPokerAction.EquippedTitleChanged -> action.updateState {
                it.copy(equippedTitle = action.title)
            }
            is PlayPokerAction.ConnectionChanged -> action.updateState {
                it.copy(connection = action.connection)
            }
        }
    }
}

// ---------- MVI types ----------

data class PlayPokerState(
    /**
     * UI-projected table state — what the screen actually renders. Derived
     * from the raw engine state via [PokerSessionFactory.tableFor], so the
     * projection logic stays out of the VM and can vary between solo (knows
     * bot personalities locally) and MP (gets occupant metadata from server).
     */
    val table: TableUiState = TableUiState.Loading,
    val occupants: List<SeatOccupant> = emptyList(),
    val cheatSheetOpen: Boolean = false,
    val xp: Long = 0,
    val lastHandXpAwarded: Int? = null,
    val recentlyEarned: List<EarnedAchievement> = emptyList(),
    val turnFeedback: TurnFeedback = TurnFeedback.Sound,
    val connection: ConnectionState = ConnectionState.Connected,
    /**
     * Which felt / table-theme the player has currently equipped. Drives
     * the screen's background paint via [feltSurfaceColor]. Default = the
     * stock app background (i.e. nothing equipped).
     */
    val equippedFelt: EquippedFelt = EquippedFelt.Default,
    /**
     * Which card-back style the player has equipped. Pushed into the
     * composition via `LocalCardBackStyle` so every face-down card on
     * the screen (opponent hole cards, deck stack, dealt-but-not-revealed
     * community cards) picks it up without prop-drilling.
     */
    val equippedCardBack: com.dangerfield.cards.libraries.ui.components.poker.CardBackStyle =
        com.dangerfield.cards.libraries.ui.components.poker.CardBackStyle.Default,
    /**
     * True when the player owns + has equipped the "Win Odds Display"
     * utility tool. Gates [humanWinPercent] computation — when false
     * the VM never runs the Monte Carlo so the cost is zero for
     * non-owners.
     */
    val winOddsToolEquipped: Boolean = false,
    /**
     * Live win-percentage for the human in the current hand, computed
     * by 400-iteration Monte Carlo over random opponent hands +
     * remaining board. Null when the tool isn't equipped, when there's
     * no active hand, or before the first computation lands. UI hides
     * the badge whenever this is null.
     *
     * Recomputed only on input changes (hole cards / community /
     * opponents-still-in-hand count), not every state tick.
     */
    val humanWinPercent: Int? = null,
    /**
     * Equipped vanity title (e.g. "The Shark") rendered under the
     * player's name. Null when nothing's equipped — UI hides the row.
     */
    val equippedTitle: String? = null,
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

    // Settings mirrors (cache flow → state)
    data class XpChanged(val totalXp: Long) : PlayPokerAction
    data class TurnFeedbackChanged(val value: TurnFeedback) : PlayPokerAction

    // Hand-end transients (internal — fired by hand-end callback)
    data class HandXpAwarded(val amount: Int) : PlayPokerAction
    data class AchievementsEarned(val earned: List<EarnedAchievement>) : PlayPokerAction

    /** Fired by the equipment subscription; repaints the table surface. */
    data class EquippedFeltChanged(val felt: EquippedFelt) : PlayPokerAction

    /** Fired by the equipment subscription; flips the ambient card back style. */
    data class EquippedCardBackChanged(
        val style: com.dangerfield.cards.libraries.ui.components.poker.CardBackStyle,
    ) : PlayPokerAction

    /** Fired by the equipment subscription; gates win-odds computation. */
    data class WinOddsToolEquippedChanged(val equipped: Boolean) : PlayPokerAction

    /** Fired by the equity flow after a fresh Monte Carlo run resolves. */
    data class WinPercentChanged(val percent: Int?) : PlayPokerAction

    /** Fired by the equipment subscription; flips the equipped title shown under the name. */
    data class EquippedTitleChanged(val title: String?) : PlayPokerAction

    /** Fired by the session's connection-state subscription. */
    data class ConnectionChanged(val connection: ConnectionState) : PlayPokerAction
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

    /**
     * Project the raw engine state into a [TableUiState] for rendering.
     *
     * The factory owns this projection because the inputs differ by session
     * type: solo knows bot personalities locally; MP will source them from
     * server-provided occupant metadata.
     *
     * [lastWinners] and [lastActionBySeat] are per-hand transients the VM
     * tracks from engine events — they aren't part of [GameState] proper
     * but the rendered table needs them (showdown dialog, "Called 50" pill).
     */
    fun tableFor(
        state: GameState,
        lastWinners: GameEvent.HandEnded? = null,
        lastActionBySeat: Map<Int, PlayerAction> = emptyMap(),
        humanIdentity: Identity? = null,
    ): TableUiState
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
