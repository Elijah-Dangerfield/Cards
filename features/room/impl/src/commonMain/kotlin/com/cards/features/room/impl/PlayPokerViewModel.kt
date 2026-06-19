package com.dangerfield.cards.features.room.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.bots.EquityBreakdown
import com.dangerfield.cards.libraries.bots.HandStrength
import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.AchievementRarity
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.BotSpeed
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.EmojiBlast
import com.dangerfield.cards.libraries.cards.EmotePackCatalog
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.TurnFeedback
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.levelProgressFor
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
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.products.ProductsRepository
import com.dangerfield.cards.libraries.ui.components.PlayerBadge
import com.dangerfield.cards.libraries.ui.components.resolvePlayerBadges
import com.dangerfield.cards.libraries.ui.components.poker.EquippedFelt
import com.dangerfield.cards.libraries.ui.components.poker.badgeEmojiForProductId
import com.dangerfield.cards.libraries.ui.components.poker.cardBackForProductId
import com.dangerfield.cards.libraries.ui.components.poker.feltForProductId
import com.dangerfield.cards.libraries.review.ReviewPromptCoordinator
import com.dangerfield.cards.libraries.review.ReviewTrigger
import com.dangerfield.cards.libraries.rooms.ClosedReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject
import kotlin.time.Clock

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
    private val inventoryRepository: InventoryRepository,
    private val productsRepository: ProductsRepository,
    private val profileRepository: ProfileRepository,
    private val reviewPromptCoordinator: ReviewPromptCoordinator,
    private val dispatcherProvider: DispatcherProvider,
    private val clock: Clock,
) : SEAViewModel<PlayPokerState, PlayPokerEvent, PlayPokerAction>(
    initialStateArg = PlayPokerState(xpMode = sessionFactory.xpMode),
) {

    private val logger = KLog.withTag("PlayPokerViewModel")

    // Construction-time hint for the session factory only. Solo seats the
    // human here; MP ignores it and allocates a seat server-side. Anything
    // that attributes a finished hand resolves the real seat via
    // [PokerSessionFactory.humanSeatIndex] against the live state instead.
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

    // Latest known authenticated profile. Captured here so the [tableFor]
    // projection can render the human seat with the user's chosen display
    // name + avatar emoji instead of the engine-side "You" / null
    // placeholders. Null until the first Authenticated emission lands;
    // re-projects when it does. Fallback profiles aren't useful here —
    // they have no display name to render.
    private var latestHumanProfile: Profile.Authenticated? = null
    private var lastGameState: GameState? = null

    // Dedupes intent submission within a single decision point. Keyed on the
    // live state's (handNumber, lastSequence) — two taps before the resulting
    // snapshot lands read the same token and the second is dropped, so a slow
    // ack / double-tap can't fire the same action twice. Cleared on a rejected
    // submit so a corrected resubmit (e.g. an illegal raise) on the same turn
    // still goes through; an accepted action advances lastSequence, so the next
    // genuine decision carries a fresh token regardless.
    private var submittedTurnToken: Pair<Int, Long>? = null

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
        // Inbound table emotes from opponents (MP only — solo never emits).
        // The handler attributes each to its seat, drops the local player's
        // own echo, and respects the per-seat mute set.
        viewModelScope.launch {
            session.emoteBlasts.collect { emote ->
                takeAction(PlayPokerAction.RemoteEmoteReceived(emote.seatIndex, emote.emoji))
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
        // Terminal room-close → one-shot exit event. A closed/rejected room
        // collapses to [ConnectionState.Disconnected] above, which the banner
        // can't tell apart from a transient drop; this is the signal that
        // there's nothing to reconnect to, so the entry point pops the screen.
        viewModelScope.launch {
            session.roomClosed.collect { reason ->
                sendEvent(PlayPokerEvent.RoomClosed(reason))
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
                takeAction(PlayPokerAction.SwipeFoldAckChanged(data.swipeFoldGestureAck))
                takeAction(PlayPokerAction.WinOddsFlipHintSeenChanged(data.winOddsFlipHintSeen))
                takeAction(PlayPokerAction.MutedEmojiPlayersChanged(data.mutedEmojiPlayerKeys))
                takeAction(PlayPokerAction.XpBoostChanged(data.xpBoostExpiresAtEpochMs))
            }
        }
        // Inventory mirror — folds owned emote-pack product IDs into the
        // blast tray's available pool. Empty when the user owns no packs;
        // the screen hides the tray UI entirely in that case.
        viewModelScope.launch {
            inventoryRepository.observeInventory().collect { items ->
                val ownedIds = items.map { it.productId }.toSet()
                takeAction(
                    PlayPokerAction.AvailableEmojisChanged(
                        EmotePackCatalog.availableEmojisFor(ownedIds),
                    ),
                )
            }
        }
        // Profile → re-project the table so the human seat picks up the
        // user's chosen display name + avatar emoji.
        viewModelScope.launch {
            profileRepository.observe().collect { profile ->
                val authed = profile as? Profile.Authenticated ?: return@collect
                latestHumanProfile = authed
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
                val badgeEmoji = entries.firstNotNullOfOrNull { badgeEmojiForProductId(it.productId) }
                takeAction(PlayPokerAction.EquippedFeltChanged(felt))
                takeAction(PlayPokerAction.EquippedCardBackChanged(cardBack))
                takeAction(PlayPokerAction.WinOddsToolEquippedChanged(winOddsTool))
                takeAction(PlayPokerAction.EquippedBadgeChanged(badgeEmoji))
            }
        }
        // Catalog-driven badges + titles (unified) for the tappable chips on the
        // player-profile sheet — name/emoji/description come from the product
        // catalog (incl. the prestige bucket), earned date from inventory.
        viewModelScope.launch {
            combine(
                equipmentRepository.observeEquipped(),
                productsRepository.observeCatalog(),
                inventoryRepository.observeInventory(),
            ) { equipped, catalog, inventory ->
                resolvePlayerBadges(
                    equippedProductIds = equipped.filter { it.isEquipped }.map { it.productId },
                    catalog = catalog,
                    inventory = inventory,
                )
            }.collect { badges ->
                takeAction(PlayPokerAction.EquippedBadgesChanged(badges))
            }
        }
        // Catalog snapshot in state so the screen can resolve an opponent's
        // badge ids (off their Seat) to display metadata when their sheet opens.
        viewModelScope.launch {
            productsRepository.observeCatalog().collect { catalog ->
                takeAction(PlayPokerAction.CatalogChanged(catalog))
            }
        }
        viewModelScope.launch { productsRepository.refresh() }
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
                    val seatIndex = sessionFactory.humanSeatIndex(gs)
                    val human = gs.seats.firstOrNull { it.index == seatIndex }
                        ?: return@combine EquityInput.NotApplicable
                    if (human.holeCards.size != 2) return@combine EquityInput.NotApplicable
                    val opponentsInHand = gs.seats.count { seat ->
                        seat.index != seatIndex &&
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
                        takeAction(PlayPokerAction.WinOddsChanged(null))
                    }
                }
                .mapLatest { input ->
                    if (input is EquityInput.Compute) {
                        withContext(dispatcherProvider.default) {
                            HandStrength.equityBreakdownVsRandom(
                                holeCards = input.hole,
                                community = input.community,
                                numOpponents = input.opponents,
                                iterations = WIN_ODDS_ITERATIONS,
                            )
                        }
                    } else null
                }
                .collect { breakdown ->
                    if (breakdown != null) {
                        takeAction(PlayPokerAction.WinOddsChanged(breakdown))
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
        /** Per product-spec.md §5.5 — 8 seconds between human-tapped emoji blasts. */
        const val EMOJI_COOLDOWN_MS: Long = 8_000
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
        // Resolve the local human's seat from the finished-hand state rather
        // than the construction-time hint: in MP the human can sit at any
        // seat, and attributing the hand to seat 0 would credit a different
        // player's fold/showdown outcome.
        val humanSeatIndex = sessionFactory.humanSeatIndex(state)
        val summary = HandResultSummaryBuilder.build(
            event = event,
            state = state,
            humanSeatIndex = humanSeatIndex,
            mode = sessionFactory.xpMode,
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
        // Mark achievement computation in flight *before* the launch so the
        // dialog's dismiss path knows to wait rather than skip a reveal that
        // hasn't been computed yet (recordHand is async — see the flag's doc).
        takeAction(PlayPokerAction.HandEndAchievementsPending)
        viewModelScope.launch {
            val priorLevel = Catching {
                levelProgressFor(progressionRepository.getProgression().totalXp).level
            }.getOrNull()

            Catching {
                val awarded = progressionRepository.awardForHand(summary)
                val total = awarded.sumOf { it.deltaXp }
                if (total > 0) takeAction(PlayPokerAction.HandXpAwarded(total))
            }.onFailure { logger.w(it) { "Awarding XP failed for hand ${summary.handId}" } }

            val earned = Catching {
                achievementRepository.recordHand(summary, context)
            }.onFailure {
                logger.w(it) { "Achievement recording failed for hand ${summary.handId}" }
            }.getOrNull().orEmpty()
            // Always resolve — even with no unlocks — so the awaiting flag clears
            // and the dismiss path can advance.
            takeAction(PlayPokerAction.AchievementsEarned(earned))

            maybeRequestReviewPrompt(priorLevel = priorLevel, earned = earned)
        }
    }

    private suspend fun maybeRequestReviewPrompt(
        priorLevel: Int?,
        earned: List<EarnedAchievement>,
    ) {
        Catching {
            val unlockedRareOrBetter = earned.any {
                it.achievement.rarity.ordinal >= AchievementRarity.RARE.ordinal
            }
            if (unlockedRareOrBetter) {
                reviewPromptCoordinator.requestPrompt(ReviewTrigger.AchievementUnlocked)
                return@Catching
            }
            if (priorLevel != null) {
                val newLevel = levelProgressFor(
                    progressionRepository.getProgression().totalXp,
                ).level
                if (newLevel > priorLevel) {
                    reviewPromptCoordinator.requestPrompt(ReviewTrigger.LevelUp)
                }
            }
        }.onFailure { logger.w(it) { "Review prompt request failed" } }
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
                            humanProfile = latestHumanProfile,
                            humanLevel = it.humanLevel,
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
                val affectsProjection = when (val ev = action.event) {
                    is GameEvent.HandStarted -> {
                        lastWinners = null
                        lastActionBySeat.clear()
                        true
                    }
                    is GameEvent.StreetAdvanced -> { lastActionBySeat.clear(); true }
                    is GameEvent.ActionTaken -> { lastActionBySeat[ev.seatIndex] = ev.action; true }
                    is GameEvent.HandEnded -> { lastWinners = ev; true }
                    else -> false
                }
                // The table projection renders these transients but is
                // otherwise only recomputed on a GameState snapshot. The
                // server emits the Complete snapshot and the HandEnded event
                // on two independent flows with no ordering guarantee — so if
                // the snapshot is projected before HandEnded arrives, the
                // winner (or a final action pill) would never show. Re-project
                // here so a transient change is reflected regardless of
                // event/snapshot ordering. (Bot sessions happened to emit the
                // event before the final state, which masked this in solo play.)
                if (affectsProjection) {
                    lastGameState?.let { gs ->
                        action.updateState {
                            it.copy(
                                table = sessionFactory.tableFor(
                                    state = gs,
                                    lastWinners = lastWinners,
                                    lastActionBySeat = lastActionBySeat.toMap(),
                                    humanProfile = latestHumanProfile,
                                    humanLevel = it.humanLevel,
                                ),
                            )
                        }
                    }
                }
            }

            is PlayPokerAction.Submit -> {
                val turnToken = lastGameState?.let { it.handNumber to it.lastSequence }
                if (turnToken != null && turnToken == submittedTurnToken) {
                    logger.d { "Ignoring duplicate Submit ${action.intent} for turn $turnToken" }
                } else {
                    submittedTurnToken = turnToken
                    logger.d { "VM received Submit ${action.intent}" }
                    viewModelScope.launch {
                        Catching { session.submit(action.intent) }
                            .onFailure { e ->
                                logger.w(e) { "submit failed for ${action.intent}" }
                                if (submittedTurnToken == turnToken) submittedTurnToken = null
                            }
                    }
                }
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

            is PlayPokerAction.XpChanged -> action.updateState { state ->
                val newLevel = levelProgressFor(action.totalXp).level
                val nextState = state.copy(xp = action.totalXp, humanLevel = newLevel)
                // Re-project the table so the human seat picks up the
                // refreshed level pill — only when the level actually
                // changed (XP ticks every hand; level changes maybe
                // once a session).
                if (newLevel != state.humanLevel) {
                    lastGameState?.let { gs ->
                        nextState.copy(
                            table = sessionFactory.tableFor(
                                state = gs,
                                lastWinners = lastWinners,
                                lastActionBySeat = lastActionBySeat.toMap(),
                                humanProfile = latestHumanProfile,
                                humanLevel = newLevel,
                            ),
                        )
                    } ?: nextState
                } else {
                    nextState
                }
            }
            is PlayPokerAction.TurnFeedbackChanged -> action.updateState {
                it.copy(turnFeedback = action.value)
            }
            is PlayPokerAction.XpBoostChanged -> action.updateState {
                it.copy(xpBoostExpiresAtEpochMs = action.expiresAtEpochMs)
            }

            is PlayPokerAction.HandXpAwarded -> action.updateState {
                it.copy(lastHandXpAwarded = action.amount)
            }
            is PlayPokerAction.HandEndAchievementsPending -> action.updateState {
                it.copy(awaitingHandEndAchievements = true, recentlyEarned = emptyList())
            }
            is PlayPokerAction.AchievementsEarned -> action.updateState {
                it.copy(recentlyEarned = action.earned, awaitingHandEndAchievements = false)
            }
            is PlayPokerAction.EquippedFeltChanged -> action.updateState {
                it.copy(equippedFelt = action.felt)
            }
            is PlayPokerAction.EquippedCardBackChanged -> action.updateState {
                it.copy(equippedCardBack = action.style)
            }
            is PlayPokerAction.WinOddsToolEquippedChanged -> action.updateState {
                // Clear any stale breakdown when the tool flips off — UI
                // hides the flip affordance on the next compose pass.
                it.copy(
                    winOddsToolEquipped = action.equipped,
                    humanWinOdds = if (action.equipped) it.humanWinOdds else null,
                )
            }
            is PlayPokerAction.WinOddsChanged -> action.updateState {
                it.copy(humanWinOdds = action.breakdown)
            }
            is PlayPokerAction.EquippedBadgeChanged -> action.updateState {
                it.copy(equippedBadgeEmoji = action.emoji)
            }
            is PlayPokerAction.EquippedBadgesChanged -> action.updateState {
                it.copy(equippedBadges = action.badges)
            }
            is PlayPokerAction.CatalogChanged -> action.updateState {
                it.copy(catalog = action.catalog)
            }
            is PlayPokerAction.ConnectionChanged -> action.updateState {
                it.copy(connection = action.connection)
            }
            is PlayPokerAction.LeaveTable -> {
                if (sessionFactory.xpMode == XpMode.BOTS) {
                    Catching {
                        reviewPromptCoordinator.requestPrompt(ReviewTrigger.SessionEnd)
                    }.onFailure { logger.w(it) { "SessionEnd review prompt request failed" } }
                }
            }
            is PlayPokerAction.SwipeFoldAckChanged -> action.updateState {
                it.copy(swipeFoldGestureAck = action.acknowledged)
            }
            is PlayPokerAction.AcknowledgeSwipeFoldGesture -> {
                viewModelScope.launch {
                    appCache.update { it.copy(swipeFoldGestureAck = true) }
                }
            }
            is PlayPokerAction.WinOddsFlipHintSeenChanged -> action.updateState {
                it.copy(winOddsFlipHintSeen = action.seen)
            }
            is PlayPokerAction.MarkWinOddsFlipHintSeen -> {
                // Fire-and-forget — the state mirror above flips on the
                // next cache emit. Writes are idempotent so repeated
                // flips (the user keeps toggling the tile) are no-ops
                // after the first.
                viewModelScope.launch {
                    appCache.update { it.copy(winOddsFlipHintSeen = true) }
                }
            }
            is PlayPokerAction.AvailableEmojisChanged -> action.updateState {
                it.copy(availableEmojis = action.emojis)
            }
            is PlayPokerAction.MutedEmojiPlayersChanged -> action.updateState {
                it.copy(mutedEmojiPlayerKeys = action.keys)
            }
            is PlayPokerAction.BlastEmoji -> {
                // Local cooldown gate. We trust state.emojiCooldownEndsAtMs
                // as the single source of truth so concurrent taps during
                // the same animation frame all see the post-emit deadline.
                val now = clock.now().toEpochMilliseconds()
                val currentState = stateFlow.value
                if (now < currentState.emojiCooldownEndsAtMs) return
                action.updateState {
                    it.copy(
                        // null emitter seat → the screen attributes the blast
                        // to the human seat (this is our own outbound emote).
                        emojiBlast = EmojiBlast(emoji = action.emoji, emittedAtEpochMs = now),
                        emojiBlastEmitterSeatIndex = null,
                        emojiCooldownEndsAtMs = now + EMOJI_COOLDOWN_MS,
                    )
                }
                // Carry it to opponents over the wire (no-op for solo bots).
                // Fire-and-forget: the local blast already rendered above, so
                // a send failure costs nobody their own animation.
                viewModelScope.launch {
                    Catching { session.sendEmote(action.emoji) }
                        .onFailure { e -> logger.w(e) { "emote send failed" } }
                }
            }
            is PlayPokerAction.RemoteEmoteReceived -> {
                val now = clock.now().toEpochMilliseconds()
                val active = stateFlow.value.table as? TableUiState.Active
                val seat = active?.seats?.firstOrNull { it.index == action.seatIndex }
                // Drop our own echo (we rendered it locally on tap) and any
                // seat the user has muted. Unknown seat → drop.
                if (seat == null || seat.isHuman) return
                if (seatMuteKey(seat) in stateFlow.value.mutedEmojiPlayerKeys) return
                action.updateState {
                    it.copy(
                        emojiBlast = EmojiBlast(emoji = action.emoji, emittedAtEpochMs = now),
                        emojiBlastEmitterSeatIndex = action.seatIndex,
                    )
                }
            }
            is PlayPokerAction.EmojiBlastConsumed -> action.updateState {
                // Identity guard: only clear if the consumed blast is still
                // the one we last emitted — protects against a "consumed"
                // arriving after a new blast has replaced it.
                if (it.emojiBlast?.emittedAtEpochMs == action.emittedAtEpochMs) {
                    it.copy(emojiBlast = null, emojiBlastEmitterSeatIndex = null)
                } else {
                    it
                }
            }
            is PlayPokerAction.ToggleMutePlayer -> {
                viewModelScope.launch {
                    appCache.update { data ->
                        val next = data.mutedEmojiPlayerKeys.toMutableSet().apply {
                            if (action.key in this) remove(action.key) else add(action.key)
                        }
                        data.copy(mutedEmojiPlayerKeys = next)
                    }
                }
            }
        }
    }

}

/**
 * Stable identity key used for muting a seat's table-side emoji. Returns
 * null for the human seat (you can't mute yourself) and the seat's display
 * name otherwise — which is the stable per-personality name for bots in
 * V1 solo. When MP/reactive blasts land, the same key wires through.
 */
fun seatMuteKey(seat: SeatView): String? = if (seat.isHuman) null else seat.displayName

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
    /**
     * Expiry of the active XP boost window (epoch-ms), or null if none. Drives
     * the inline countdown grafted onto the level pill while a boost burns.
     */
    val xpBoostExpiresAtEpochMs: Long? = null,
    /**
     * Local user's derived level from [xp]. Mirrored into [TableUiState]
     * via the session factory so the human seat shows a "Lvl N" pill
     * below the avatar. Null until the first progression emission lands.
     */
    val humanLevel: Int? = null,
    val lastHandXpAwarded: Int? = null,
    val recentlyEarned: List<EarnedAchievement> = emptyList(),
    /**
     * True from hand-end until [recordHand][AchievementRepository.recordHand]
     * resolves. Achievement computation is async (a string of DB writes), so a
     * fast "next hand" tap could otherwise advance past a reveal that hadn't
     * been computed yet. The bot-mode dismiss path waits on this flag so a
     * freshly-earned achievement can't be skipped.
     */
    val awaitingHandEndAchievements: Boolean = false,
    val turnFeedback: TurnFeedback = TurnFeedback.Vibrate,
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
     * utility tool. Gates [humanWinOdds] computation — when false
     * the VM never runs the Monte Carlo so the cost is zero for
     * non-owners.
     */
    val winOddsToolEquipped: Boolean = false,
    /**
     * Live win/tie/lose breakdown for the human in the current hand,
     * computed by 400-iteration Monte Carlo over random opponent hands
     * + remaining board. Null when the tool isn't equipped, when
     * there's no active hand, or before the first computation lands.
     * UI hides the flip affordance whenever this is null.
     *
     * Recomputed only on input changes (hole cards / community /
     * opponents-still-in-hand count), not every state tick.
     */
    val humanWinOdds: EquityBreakdown? = null,
    /**
     * Emoji of the equipped permanent seat badge (founding-member,
     * league rewards, etc.) rendered in the slot mirrored opposite the
     * SB/BB chip on the human seat. Null = empty slot.
     */
    val equippedBadgeEmoji: String? = null,
    /**
     * The human's equipped badges + titles (unified), resolved from the catalog,
     * shown as tappable chips on the player-profile sheet (tap → read about it).
     */
    val equippedBadges: List<PlayerBadge> = emptyList(),
    /**
     * The product catalog (incl. the prestige badge/title bucket). Used to
     * resolve an *opponent's* equipped badge ids (which ride the engine Seat)
     * into display metadata when their profile sheet opens.
     */
    val catalog: com.dangerfield.cards.libraries.products.ProductCatalog =
        com.dangerfield.cards.libraries.products.ProductCatalog.Empty,
    /**
     * Mirrors `AppData.swipeFoldGestureAck`. False = swipe-up-to-fold on
     * the human's hole cards opens a confirmation dialog; true = it folds
     * silently. Flips the moment the user ticks "Don't show this again"
     * inside that dialog.
     */
    val swipeFoldGestureAck: Boolean = false,

    /**
     * Mirrors `AppData.winOddsFlipHintSeen`. False = the player info
     * tile plays a one-shot discoverability wiggle once per session
     * when the win-odds tool is owned. Flips to true the first time
     * the user actually taps to flip the tile — after which the wiggle
     * never plays again.
     */
    val winOddsFlipHintSeen: Boolean = false,

    /**
     * Emojis the user can blast from the in-game tray. Sourced entirely
     * from owned `emotes_*` packs — users with no pack get an empty list
     * and the tray UI hides. Order is stable across reorderings of
     * inventory.
     */
    val availableEmojis: List<String> = emptyList(),

    /**
     * Per-seat mute set, mirrored from `AppData.mutedEmojiPlayerKeys`.
     * Keys come from [seatMuteKey]. Read by the avatar-tap surface to
     * show the toggle's current state. Today no inbound emoji exists
     * (single-player vs bots is one-way), so this set drives no
     * filtering yet — it's forward-infrastructure for MP / V1.x
     * reactive-bot blasts (product-spec.md §5.5).
     */
    val mutedEmojiPlayerKeys: Set<String> = emptySet(),

    /**
     * The current blast animation the screen should render full-screen.
     * Set the moment the VM accepts a [PlayPokerAction.BlastEmoji];
     * cleared when the screen reports the animation finished via
     * [PlayPokerAction.EmojiBlastConsumed].
     */
    val emojiBlast: EmojiBlast? = null,

    /**
     * Seat the active [emojiBlast] was thrown from, or null when it's the
     * local human's own outbound blast. The screen resolves this to the
     * emitter's avatar so an opponent's emote reads as "Bob threw this";
     * null falls back to the human seat (preserving solo behavior).
     */
    val emojiBlastEmitterSeatIndex: Int? = null,

    /**
     * Epoch-ms after which the user can blast again. 0 = no cooldown
     * active. Compared against `clock.now()` server-side (VM owns the
     * clock) to gate `BlastEmoji`; the screen reads this to dim the
     * tray and show a countdown.
     */
    val emojiCooldownEndsAtMs: Long = 0L,

    /**
     * Whether this session counts as bot or multiplayer play. Constant for
     * the lifetime of the screen — set from `PokerSessionFactory.xpMode` at
     * VM construction. Drives the achievement-unlock celebration split:
     * bots get a full-bleed [AchievementCelebrationSheet] sequenced after
     * the showdown / bust dialog dismisses; multiplayer keeps the inline
     * row on the dialog itself.
     */
    val xpMode: XpMode = XpMode.BOTS,
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
    data class XpBoostChanged(val expiresAtEpochMs: Long?) : PlayPokerAction

    // Hand-end transients (internal — fired by hand-end callback)
    data class HandXpAwarded(val amount: Int) : PlayPokerAction
    data object HandEndAchievementsPending : PlayPokerAction
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
    data class WinOddsChanged(val breakdown: EquityBreakdown?) : PlayPokerAction

    /** Fired by the equipment subscription; flips the equipped permanent seat badge. */
    data class EquippedBadgeChanged(val emoji: String?) : PlayPokerAction

    /** The human's equipped badges + titles, resolved from the catalog, for the
     *  tappable chips on the player-profile sheet. */
    data class EquippedBadgesChanged(val badges: List<PlayerBadge>) : PlayPokerAction

    /** Catalog snapshot — lets the screen resolve an opponent's badge ids. */
    data class CatalogChanged(
        val catalog: com.dangerfield.cards.libraries.products.ProductCatalog,
    ) : PlayPokerAction

    /** Fired by the session's connection-state subscription. */
    data class ConnectionChanged(val connection: ConnectionState) : PlayPokerAction

    /**
     * Fired by the play screen the moment the user opts into a clean
     * exit (back-handler, top-bar back, confirmed leave dialog). The VM
     * uses this to fire [ReviewTrigger.SessionEnd] — a "they finished
     * intentionally" signal that the OS may decide to act on. No state
     * update; navigation itself is the screen's job.
     */
    data object LeaveTable : PlayPokerAction

    /** Fired by the AppCache mirror; flips the swipe-fold confirmation gate. */
    data class SwipeFoldAckChanged(val acknowledged: Boolean) : PlayPokerAction

    /**
     * Fired by the swipe-fold confirmation dialog when the user ticks
     * "Don't show this again". Writes through to AppCache so the gate
     * stays flipped across sessions.
     */
    data object AcknowledgeSwipeFoldGesture : PlayPokerAction

    /** Fired by the AppCache mirror; gates the win-odds flip-tile wiggle hint. */
    data class WinOddsFlipHintSeenChanged(val seen: Boolean) : PlayPokerAction

    /**
     * Fired by the player info tile the first time the user actually
     * flips it during a session. Writes through to AppCache so the
     * wiggle hint never re-plays on this account.
     */
    data object MarkWinOddsFlipHintSeen : PlayPokerAction

    /** Fired by the inventory subscription. */
    data class AvailableEmojisChanged(val emojis: List<String>) : PlayPokerAction

    /** Fired by the AppCache mirror. */
    data class MutedEmojiPlayersChanged(val keys: Set<String>) : PlayPokerAction

    /**
     * Fired when the user taps an emoji in the in-game tray. VM gates on
     * the current cooldown deadline; ignored if still cooling. On accept,
     * sets [PlayPokerState.emojiBlast] for the screen to animate.
     */
    data class BlastEmoji(val emoji: String) : PlayPokerAction

    /**
     * Fired by the session's emote subscription when an opponent blasts a
     * table emote (MP only). The VM attributes it to [seatIndex], drops
     * the local human's own echo and muted seats, then renders it through
     * the same blast overlay as an outbound emote.
     */
    data class RemoteEmoteReceived(val seatIndex: Int, val emoji: String) : PlayPokerAction

    /**
     * Fired by the screen when the 1.5s blast animation finishes. The
     * emit timestamp guards against clearing a freshly-replaced blast.
     */
    data class EmojiBlastConsumed(val emittedAtEpochMs: Long) : PlayPokerAction

    /**
     * Fired by the avatar-tap mute sheet. Idempotent toggle on the
     * persisted set in AppCache.
     */
    data class ToggleMutePlayer(val key: String) : PlayPokerAction
}

sealed interface PlayPokerEvent {
    data object NavigatedBack : PlayPokerEvent
    data class PlayHaptic(val kind: HapticKind) : PlayPokerEvent
    data class PlaySound(val kind: SoundKind) : PlayPokerEvent

    /**
     * The room was closed out from under us mid-session — the server GC'd
     * it or refused the subscription. Terminal: the entry point pops the
     * play screen so the user isn't stranded on a spinning "reconnecting"
     * banner. Only ever fires for multiplayer; local-bots rooms can't close.
     */
    data class RoomClosed(val reason: ClosedReason) : PlayPokerEvent
}

enum class HapticKind { ActionTaken, HandWon, HandLost, Bust, LevelUp }
enum class SoundKind { CardFlick, ChipClick, Showdown }

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

    /**
     * Which [XpMode] this session counts for. Drives progression
     * attribution (hand summaries written under this mode) and gating
     * for prestige-bearing signals like [ReviewTrigger.SessionEnd] —
     * MP-disconnects shouldn't masquerade as positive moments.
     */
    val xpMode: XpMode

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
     * The local human's seat index within [state]. Solo sessions seat the
     * human at a fixed index; MP seats them at whatever index the server
     * allocated, so this matches the local user id against each seat. Per-
     * hand attribution (XP, achievements, win-odds) keys off this — using a
     * hard-coded seat would credit the wrong player whenever the local human
     * isn't at seat 0. Returns `-1` when the local human isn't seated in
     * [state] (pre-first-snapshot, or a spectator) so attribution degrades to
     * "no credit" rather than crediting another seat's outcome.
     */
    fun humanSeatIndex(state: GameState): Int

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
        humanProfile: Profile.Authenticated? = null,
        /** Local user's derived level (`levelProgressFor(xp).level`); null
         *  while progression hasn't resolved yet. */
        humanLevel: Int? = null,
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
        // Derived from the server-snapshotted Seat.xp; 0 until it resolves.
        level = seat.xp?.let { levelProgressFor(it).level } ?: 0,
        leagueTier = null,     // sourced from league repo (V1.1)
    )
}
