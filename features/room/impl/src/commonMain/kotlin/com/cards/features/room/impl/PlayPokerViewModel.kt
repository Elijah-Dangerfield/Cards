package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.IntentRejectedException
import com.dangerfield.cards.features.room.impl.session.PokerSession
import com.dangerfield.cards.features.room.impl.session.PokerSessionFactory
import com.dangerfield.cards.features.room.impl.usecase.EmoteGate
import com.dangerfield.cards.features.room.impl.usecase.HandEndProgression
import com.dangerfield.cards.features.room.impl.usecase.HandResultSummaryBuilder
import com.dangerfield.cards.features.room.impl.usecase.PlayStyleHandSummaryBuilder
import com.dangerfield.cards.features.room.impl.usecase.PlayerStatHandSummaryBuilder
import com.dangerfield.cards.features.room.impl.usecase.WinOddsEngine

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.billing.IapPurchaseOutcome
import com.dangerfield.cards.libraries.billing.PurchaseChipPackUseCase
import com.dangerfield.cards.libraries.cards.AchievementRarity
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.BotSpeed
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.EmojiBlast
import com.dangerfield.cards.libraries.cards.EmotePackCatalog
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.PlayStyleRepository
import com.dangerfield.cards.libraries.cards.PlayerStatsRepository
import com.dangerfield.cards.libraries.cards.ProgressionConfig
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.LevelCurve
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.products.ProductsRepository
import com.dangerfield.cards.libraries.ui.components.resolvePlayerBadges
import com.dangerfield.cards.libraries.ui.components.poker.EquippedFelt
import com.dangerfield.cards.libraries.ui.components.poker.badgeEmojiForProductId
import com.dangerfield.cards.libraries.ui.components.poker.cardBackForProductId
import com.dangerfield.cards.libraries.ui.components.poker.feltForProductId
import com.dangerfield.cards.libraries.review.ReviewPromptCoordinator
import com.dangerfield.cards.libraries.review.ReviewTrigger
import com.dangerfield.cards.libraries.social.FriendRepository
import com.dangerfield.cards.libraries.social.SendFriendRequestResult
import com.dangerfield.cards.libraries.social.SocialEnabled
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
 * Session-agnostic ViewModel behind the play-poker screen. Consumes a
 * [PokerSession] via an injected [PokerSessionFactory] (solo bots or remote MP).
 * Takes the factory, not the session, so the hand-end lambda can close over
 * `viewModelScope`; the session is built in `init`. [PlayPokerState.table] is
 * projected from [GameState] by the factory, with per-hand transients (winners,
 * action pills) tracked from engine events.
 */
@OptIn(ExperimentalCoroutinesApi::class) // mapLatest — needed for cancel-in-flight equity math
class PlayPokerViewModel @Inject constructor(
    @Assisted private val sessionFactory: PokerSessionFactory,
    private val progressionRepository: ProgressionRepository,
    private val playStyleRepository: PlayStyleRepository,
    private val playerStatsRepository: PlayerStatsRepository,
    private val progressionConfig: ProgressionConfig,
    private val achievementRepository: AchievementRepository,
    private val appCache: AppCache,
    private val equipmentRepository: EquipmentRepository,
    private val inventoryRepository: InventoryRepository,
    private val productsRepository: ProductsRepository,
    private val chipsRepository: ChipsRepository,
    private val purchaseChipPack: PurchaseChipPackUseCase,
    private val profileRepository: ProfileRepository,
    private val friendRepository: FriendRepository,
    private val reviewPromptCoordinator: ReviewPromptCoordinator,
    private val leaveCashOutNotifier: LeaveCashOutNotifier,
    private val dispatcherProvider: DispatcherProvider,
    private val appScope: AppCoroutineScope,
    private val clock: Clock,
    socialEnabledConfig: SocialEnabled,
) : SEAViewModel<PlayPokerState, PlayPokerEvent, PlayPokerAction>(
    initialStateArg = PlayPokerState(
        xpMode = sessionFactory.xpMode,
        socialEnabled = socialEnabledConfig(),
        roomCode = sessionFactory.roomCode,
    ),
) {

    private val logger = KLog.withTag("PlayPokerViewModel")

    // Server-tunable level curve; read live so a mid-session retune reflects on
    // the next projection.
    private val levelCurve: LevelCurve get() = progressionConfig.levelCurve()

    // Construction-time hint only. Real per-hand attribution resolves the seat
    // via [PokerSessionFactory.humanSeatIndex] against live state (MP seats vary).
    private val humanSeatIndex: Int = 0

    // Mirror read by the session each bot turn so a mid-hand speed toggle applies next.
    private var latestBotSpeed: BotSpeed = BotSpeed.Normal

    // Per-hand transients fed into the table projection (not part of GameState);
    // tracked from events, cleared each hand.
    private var lastWinners: GameEvent.HandEnded? = null
    private val lastActionBySeat: MutableMap<Int, PlayerAction> = mutableMapOf()

    // Tallies the human's actions across each hand off the event stream so the
    // hand's play-style contribution can be recorded at HandEnded. Driven only
    // from the single GameEventReceived collector (ordered: actions before end).
    private val playStyleBuilder = PlayStyleHandSummaryBuilder()

    // Hand number of the last play-style row we recorded — guards against a
    // re-delivered HandEnded recording the same hand twice (the outbox feeds a
    // server aggregate, so a double-count silently skews the user's style).
    private var lastRecordedPlayStyleHand: Int? = null

    // Builds the per-hand server-stats contribution; stateful for the
    // order-dependent no-bust streak (seeded from the cached snapshot).
    private val playerStatBuilder = PlayerStatHandSummaryBuilder()

    // Hand number of the last player-stat row we recorded — same double-count
    // guard as play-style; the outbox feeds the server's authoritative counters.
    private var lastRecordedStatHand: Int? = null

    // Opponents we've already fired a friend request at this session — guards a
    // double-tap from sending twice (the inline button also flips to Sent).
    private val requestedFriendIds: MutableSet<String> = mutableSetOf()

    // Authenticated profile for the human-seat projection (display name + avatar).
    // Null until the first Authenticated emission; fallback profiles are ignored.
    private var latestHumanProfile: Profile.Authenticated? = null
    private var lastGameState: GameState? = null

    // Dedupes a submit within one decision point, keyed on (handNumber,
    // lastSequence): a double-tap before the next snapshot is dropped. Cleared on
    // rejection so a corrected resubmit still goes through.
    private var submittedTurnToken: Pair<Int, Long>? = null

    // Created here so the hand-end lambda can reference `viewModelScope`.
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
                takeAction(PlayPokerAction.OccupantsUpdated(sessionFactory.occupantsFor(gs, levelCurve)))
            }
        }
        // Engine events → SEA pipeline (animations, telemetry, achievement triggers)
        viewModelScope.launch {
            session.events.collect { ev ->
                takeAction(PlayPokerAction.GameEventReceived(ev))
            }
        }
        // Inbound opponent emotes (MP only); the handler drops own-echo + muted.
        viewModelScope.launch {
            session.emoteBlasts.collect { emote ->
                takeAction(PlayPokerAction.RemoteEmoteReceived(emote.seatIndex, emote.emoji))
            }
        }
        // Bootstrap the bot loop (no-op for remote sessions — they're server-driven).
        viewModelScope.launch {
            sessionFactory.bootstrap(session)
        }
        // Connection health → state; the screen banners anything but Connected.
        viewModelScope.launch {
            session.connectionState.collect { conn ->
                takeAction(PlayPokerAction.ConnectionChanged(conn))
            }
        }
        // Terminal room-close → one-shot exit (Disconnected alone can't be told
        // from a transient drop); the entry point pops the screen.
        viewModelScope.launch {
            session.roomClosed.collect { reason ->
                sendEvent(PlayPokerEvent.RoomClosed(reason))
            }
        }
        // Last human standing — distinct from roomClosed (the room still exists);
        // the entry point routes by room kind. Never fires for solo bots.
        viewModelScope.launch {
            session.opponentsLeft.collect {
                sendEvent(PlayPokerEvent.OpponentsLeft)
            }
        }
        // A non-last opponent left while others remain — surface a notice; the
        // seat renders vacated off the next snapshot. Never fires for solo bots.
        viewModelScope.launch {
            session.opponentLeft.collect { displayName ->
                sendEvent(PlayPokerEvent.OpponentLeft(displayName))
            }
        }
        // Server refused the next hand (heads-up bust, no rebuy yet) — surface
        // it so the winner's tap isn't a silent no-op. Never fires for solo bots.
        viewModelScope.launch {
            session.nextHandUnavailable.collect {
                sendEvent(PlayPokerEvent.NextHandUnavailable)
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
                takeAction(
                    PlayPokerAction.AchievementSettingsHintVisibilityChanged(
                        data.achievementPopupHintShows < ACHIEVEMENT_HINT_MAX_SHOWS,
                    ),
                )
            }
        }
        // Owned emote-pack IDs → blast-tray pool (empty hides the tray).
        viewModelScope.launch {
            inventoryRepository.observeInventory().collect { items ->
                val ownedIds = items.map { it.productId }.toSet()
                takeAction(
                    PlayPokerAction.AvailableEmojisChanged(
                        EmotePackCatalog.availableEmojisFor(ownedIds),
                    ),
                )
                takeAction(
                    PlayPokerAction.OwnsOpponentStyleReaderChanged(
                        TOOL_OPPONENT_STYLE_PRODUCT_ID in ownedIds,
                    ),
                )
            }
        }
        // Own derived play-style → self-card radar.
        viewModelScope.launch {
            playStyleRepository.observeOwnStyle().collect { style ->
                takeAction(PlayPokerAction.OwnPlayStyleChanged(style))
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
        // Equipped cosmetics → mid-session repaint. The flow is newest-first, so
        // pick the first non-Default per slot; also surfaces the win-odds tool flag.
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
        // Equipped badges/titles resolved from catalog + inventory for the
        // profile-sheet chips.
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
        // Catalog in state so the screen can resolve opponents' badge ids — and
        // so the MP bust quick-buy sheet has chip packs to show.
        viewModelScope.launch {
            productsRepository.observeCatalog().collect { catalog ->
                takeAction(PlayPokerAction.CatalogChanged(catalog))
            }
        }
        viewModelScope.launch { productsRepository.refresh() }
        // Wallet balance mirror — drives the bust dialog's rebuy gate (can the
        // player afford the buy-in?) and the quick-buy balance line.
        viewModelScope.launch {
            chipsRepository.observeBalance().collect { balance ->
                takeAction(PlayPokerAction.ChipsChanged(balance))
            }
        }
        // Live win-odds (gated in WinOddsEngine). distinctUntilChanged + mapLatest
        // cancel the in-flight Monte Carlo when any equity input shifts.
        viewModelScope.launch {
            combine(session.gameStateFlow, stateFlow) { gs, vmState ->
                WinOddsEngine.inputFor(
                    state = gs,
                    humanSeatIndex = sessionFactory.humanSeatIndex(gs),
                    toolEquipped = vmState.winOddsToolEquipped,
                )
            }
                .distinctUntilChanged()
                .onEach { input ->
                    if (input is WinOddsEngine.EquityInput.NotApplicable) {
                        takeAction(PlayPokerAction.WinOddsChanged(null))
                    }
                }
                .mapLatest { input ->
                    if (input is WinOddsEngine.EquityInput.Compute) {
                        withContext(dispatcherProvider.default) {
                            WinOddsEngine.compute(input, WIN_ODDS_ITERATIONS)
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

    private companion object {
        const val TOOL_WIN_ODDS_PRODUCT_ID = "tool_win_odds"
        const val TOOL_OPPONENT_STYLE_PRODUCT_ID = "tool_opponent_style"
        /**
         * The "you can turn these off in Settings" footer rides the first few
         * celebration sheets, then never shows again — long enough for a new
         * user to learn the toggle exists without nagging a regular.
         */
        const val ACHIEVEMENT_HINT_MAX_SHOWS = 3
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

    /**
     * Feed the per-hand play-style accumulator. Resets on a new hand, tallies
     * the human's actions, and records the hand's contribution at HandEnded.
     * Resolves the human seat from live state (MP seats vary; solo is 0).
     */
    private fun accumulatePlayStyle(event: GameEvent) {
        val humanIdx = lastGameState?.let { sessionFactory.humanSeatIndex(it) } ?: humanSeatIndex
        when (event) {
            is GameEvent.HandStarted -> playStyleBuilder.reset()
            is GameEvent.BlindPosted -> playStyleBuilder.onBlindPosted(event, humanIdx)
            is GameEvent.StreetAdvanced -> playStyleBuilder.onStreetAdvanced(event)
            is GameEvent.ActionTaken -> playStyleBuilder.onActionTaken(event, humanIdx)
            is GameEvent.HandEnded -> {
                val state = lastGameState ?: return
                // Record each hand at most once, even if HandEnded is re-delivered.
                if (state.handNumber == lastRecordedPlayStyleHand) return
                val summary = playStyleBuilder.build(
                    event = event,
                    state = state,
                    humanSeatIndex = humanIdx,
                    mode = sessionFactory.xpMode,
                ) ?: return
                lastRecordedPlayStyleHand = state.handNumber
                viewModelScope.launch {
                    Catching { playStyleRepository.recordHand(summary) }
                        .onFailure { logger.w(it) { "Recording play-style failed for hand ${summary.handId}" } }
                }
            }
            else -> Unit
        }
    }

    private fun handleHandEnded(
        event: GameEvent.HandEnded,
        state: GameState,
        humanStartingStack: Long,
    ) {
        // Resolve the human's seat from live state (MP seats vary) so the hand
        // is attributed to the right player.
        val humanSeatIndex = sessionFactory.humanSeatIndex(state)
        val summary = HandResultSummaryBuilder.build(
            event = event,
            state = state,
            humanSeatIndex = humanSeatIndex,
            mode = sessionFactory.xpMode,
        )
        // One-off audio/haptic feedback for the hand result (pure derivation —
        // empty when the human isn't seated, so no other seat's outcome leaks).
        HandEndProgression.feedbackEvents(event, state, humanSeatIndex)
            .forEach { sendEvent(it) }
        val context = HandEndProgression.achievementContext(
            state = state,
            humanSeatIndex = humanSeatIndex,
            humanStartingStack = humanStartingStack,
            difficultyName = sessionFactory.difficultyName,
        )
        // Mark in-flight before the async launch so the dismiss path waits for
        // the reveal instead of skipping it.
        takeAction(PlayPokerAction.HandEndAchievementsPending)
        viewModelScope.launch {
            val priorLevel = Catching {
                levelProgressFor(progressionRepository.getProgression().totalXp, levelCurve).level
            }.getOrNull()

            Catching {
                val awarded = progressionRepository.awardForHand(summary)
                val total = awarded.sumOf { it.deltaXp }
                if (total > 0) takeAction(PlayPokerAction.HandXpAwarded(total))
            }.onFailure { logger.w(it) { "Awarding XP failed for hand ${summary.handId}" } }

            recordPlayerStat(summary, context)

            val earned = Catching {
                achievementRepository.recordHand(summary, context)
            }.onFailure {
                logger.w(it) { "Achievement recording failed for hand ${summary.handId}" }
            }.getOrNull().orEmpty()
            // Recording always runs (the unlock is banked regardless), but the
            // user can silence the reveal in Settings. When off we surface an
            // empty list so the celebration sheet and the inline showdown/bust
            // rows show nothing — the unlock still appears later in their
            // achievements list. Always resolve — even with no unlocks — so the
            // awaiting flag clears and the dismiss path can advance.
            val surfaced = if (appCache.get().showAchievementPopups) earned else emptyList()
            takeAction(PlayPokerAction.AchievementsEarned(surfaced))

            // The review prompt keys off the *real* unlocks, not what we showed —
            // a silenced celebration shouldn't also suppress a review ask.
            maybeRequestReviewPrompt(priorLevel = priorLevel, earned = earned)
        }
    }

    /**
     * Record this hand's contribution to the server-authoritative player stats.
     * Guarded against a re-delivered HandEnded double-counting the hand (the
     * outbox feeds the server's cumulative counters). The builder's no-bust
     * streak is seeded from the last synced snapshot so a session that resumes
     * mid-streak keeps counting.
     */
    private suspend fun recordPlayerStat(
        summary: HandResultSummary,
        context: AchievementHandContext,
    ) {
        val handNumber = summary.handId.toIntOrNull()
        if (handNumber != null && handNumber == lastRecordedStatHand) return
        Catching {
            playerStatBuilder.seedStreak(
                playerStatsRepository.getStats()?.currentNoBustStreak ?: 0L,
            )
            playerStatsRepository.recordHand(playerStatBuilder.build(summary, context))
        }.onFailure {
            logger.w(it) { "Player-stat recording failed for hand ${summary.handId}" }
        }
        if (handNumber != null) lastRecordedStatHand = handNumber
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
                    levelCurve,
                ).level
                if (newLevel > priorLevel) {
                    sendEvent(PlayPokerEvent.PlayHaptic(HapticKind.LevelUp))
                    reviewPromptCoordinator.requestPrompt(ReviewTrigger.LevelUp)
                }
            }
        }.onFailure { logger.w(it) { "Review prompt request failed" } }
    }

    private suspend fun leaveAndReconcileWallet() {
        Catching { session.leave() }
            .onFailure { e -> logger.w(e) { "room leave failed" } }
        // On a real-chip table the server cashes the leaver's final stack back to
        // the wallet, but the client only learns the new balance on the next
        // sync. Without this the won pot stays invisible until the next cold
        // boot / foreground (CARDS-3C: "won 500, wallet unchanged, +100 later").
        if (sessionFactory.xpMode != XpMode.MULTIPLAYER) return

        val balanceBefore = chipsRepository.getBalance()
        Catching { chipsRepository.sync() }
            .onFailure { e -> logger.w(e) { "wallet sync after leave failed" } }
        // MP-6: the sync above reconciles the credited stack into the balance,
        // but a silent number change reads as a glitch. Confirm the credit on
        // the surface the player lands on so the wallet bump never surprises
        // them (Sentry CARDS-2N / 2Y). Only fire on a real gain — a lost stack
        // or empty leave stays quiet.
        val balanceAfter = chipsRepository.getBalance()
        if (balanceBefore == null || balanceAfter == null) return
        val credited = balanceAfter - balanceBefore
        if (credited > 0L) {
            leaveCashOutNotifier.confirmCredit(credited = credited, balanceAfter = balanceAfter)
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
                            humanProfile = latestHumanProfile,
                            humanLevel = it.humanLevel,
                            curve = levelCurve,
                        ),
                    )
                }
            }
            is PlayPokerAction.OccupantsUpdated -> action.updateState {
                it.copy(occupants = action.occupants)
            }
            is PlayPokerAction.GameEventReceived -> {
                // Tally the human's play-style off the same ordered event stream
                // (actions always precede this hand's HandEnded here).
                accumulatePlayStyle(action.event)
                // Track projection transients GameState can't carry: the HandEnded
                // winners (showdown) and the per-seat action pills.
                val affectsProjection = when (val ev = action.event) {
                    is GameEvent.HandStarted -> {
                        lastWinners = null
                        lastActionBySeat.clear()
                        // Cards hitting the felt as a fresh hand is dealt.
                        sendEvent(PlayPokerEvent.PlaySound(SoundKind.CardFlick))
                        true
                    }
                    is GameEvent.HoleCardsDealt -> {
                        // The human's own hole cards sliding in — one flick, not
                        // one per seat (this fires once per seat dealt).
                        if (ev.seatIndex == lastGameState?.let { sessionFactory.humanSeatIndex(it) }) {
                            sendEvent(PlayPokerEvent.PlaySound(SoundKind.CardFlick))
                        }
                        false
                    }
                    is GameEvent.StreetAdvanced -> { lastActionBySeat.clear(); true }
                    is GameEvent.ActionTaken -> { lastActionBySeat[ev.seatIndex] = ev.action; true }
                    is GameEvent.HandEnded -> { lastWinners = ev; true }
                    else -> false
                }
                // Snapshot and event ride independent flows with no ordering
                // guarantee, so re-project here — otherwise a Complete snapshot
                // projected before HandEnded would never show the winner/pill.
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
                                    curve = levelCurve,
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
                    // Haptic on every action; chip sound only when chips move.
                    sendEvent(PlayPokerEvent.PlayHaptic(HapticKind.ActionTaken))
                    val movesChips = action.intent is PlayerIntent.Call ||
                        action.intent is PlayerIntent.Bet ||
                        action.intent is PlayerIntent.Raise ||
                        action.intent is PlayerIntent.AllIn
                    if (movesChips) sendEvent(PlayPokerEvent.PlaySound(SoundKind.ChipClick))
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
                val newLevel = levelProgressFor(action.totalXp, levelCurve).level
                val nextState = state.copy(xp = action.totalXp, humanLevel = newLevel)
                // Re-project for the level pill only when the level actually
                // changed (XP ticks every hand; level rarely).
                if (newLevel != state.humanLevel) {
                    lastGameState?.let { gs ->
                        nextState.copy(
                            table = sessionFactory.tableFor(
                                state = gs,
                                lastWinners = lastWinners,
                                lastActionBySeat = lastActionBySeat.toMap(),
                                humanProfile = latestHumanProfile,
                                humanLevel = newLevel,
                                curve = levelCurve,
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
            is PlayPokerAction.AchievementSettingsHintVisibilityChanged -> action.updateState {
                it.copy(showAchievementSettingsHint = action.show)
            }
            is PlayPokerAction.MarkAchievementSettingsHintShown -> {
                viewModelScope.launch {
                    appCache.update {
                        it.copy(achievementPopupHintShows = it.achievementPopupHintShows + 1)
                    }
                }
            }
            is PlayPokerAction.EquippedFeltChanged -> action.updateState {
                it.copy(equippedFelt = action.felt)
            }
            is PlayPokerAction.EquippedCardBackChanged -> action.updateState {
                it.copy(equippedCardBack = action.style)
            }
            is PlayPokerAction.WinOddsToolEquippedChanged -> action.updateState {
                // Clear a stale breakdown when the tool flips off.
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
                // On appScope, not viewModelScope: the screen pops this VM the
                // instant it fires LeaveTable, but the leave must still reach the
                // server. No-op for solo.
                appScope.launch { leaveAndReconcileWallet() }
            }
            is PlayPokerAction.LeaveGameFromBust -> {
                // Same teardown as LeaveTable, on appScope so it lands as the
                // screen routes away.
                appScope.launch { leaveAndReconcileWallet() }
            }
            is PlayPokerAction.OpenQuickBuy -> action.updateState { it.copy(quickBuyOpen = true) }
            is PlayPokerAction.DismissQuickBuy -> action.updateState { it.copy(quickBuyOpen = false) }
            is PlayPokerAction.ChipsChanged -> action.updateState { it.copy(chipBalance = action.balance) }
            is PlayPokerAction.ConfirmQuickBuy -> {
                action.updateState { it.copy(purchaseInFlight = true) }
                viewModelScope.launch {
                    val outcome = Catching { purchaseChipPack(action.pack) }
                        .getOrElse { e ->
                            logger.w(e) { "quick-buy purchase failed" }
                            IapPurchaseOutcome.Failed(e.message ?: "Couldn't complete purchase")
                        }
                    action.updateState { it.copy(quickBuyOpen = false, purchaseInFlight = false) }
                    when (outcome) {
                        IapPurchaseOutcome.ClaimAccountRequired ->
                            sendEvent(PlayPokerEvent.ClaimAccountRequired)
                        else -> sendEvent(PlayPokerEvent.QuickBuyFinished(outcome))
                    }
                    // Flush the credit so the bust dialog's rebuy gate sees the
                    // fresh balance before the player taps Rebuy.
                    if (outcome is IapPurchaseOutcome.Success || outcome is IapPurchaseOutcome.AlreadyOwned) {
                        Catching { chipsRepository.sync() }
                            .onFailure { e -> logger.w(e) { "chip sync after quick-buy failed" } }
                    }
                }
            }
            is PlayPokerAction.Rebuy -> {
                // On viewModelScope (unlike LeaveGameFromBust): the player is
                // staying, so the rebuy round-trip must outlive the action but
                // not the screen.
                viewModelScope.launch {
                    Catching { session.rebuy() }
                        .onSuccess { sendEvent(PlayPokerEvent.RebuySucceeded) }
                        .onFailure { e ->
                            if (e is IntentRejectedException &&
                                e.reason.contains("insufficient", ignoreCase = true)
                            ) {
                                sendEvent(PlayPokerEvent.RebuyInsufficientChips)
                            } else {
                                logger.w(e) { "rebuy failed" }
                            }
                        }
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
                // Write-through; the state mirror flips on the next cache emit.
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
                val now = clock.now().toEpochMilliseconds()
                val currentState = stateFlow.value
                if (!EmoteGate.canBlast(now, currentState.emojiCooldownEndsAtMs)) return
                action.updateState {
                    it.copy(
                        // null emitter seat → the screen attributes it to the human.
                        emojiBlast = EmojiBlast(emoji = action.emoji, emittedAtEpochMs = now),
                        emojiBlastEmitterSeatIndex = null,
                        emojiCooldownEndsAtMs = now + EMOJI_COOLDOWN_MS,
                    )
                }
                // Carry to opponents (no-op for solo); fire-and-forget — we
                // already rendered locally.
                viewModelScope.launch {
                    Catching { session.sendEmote(action.emoji) }
                        .onFailure { e -> logger.w(e) { "emote send failed" } }
                }
            }
            is PlayPokerAction.RemoteEmoteReceived -> {
                val now = clock.now().toEpochMilliseconds()
                val active = stateFlow.value.table as? TableUiState.Active
                val seat = active?.seats?.firstOrNull { it.index == action.seatIndex }
                // Drop our own echo (rendered locally on tap), a muted seat, and
                // an unknown seat — see EmoteGate.shouldRenderRemote.
                if (!EmoteGate.shouldRenderRemote(seat, stateFlow.value.mutedEmojiPlayerKeys)) return
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
            is PlayPokerAction.OwnPlayStyleChanged -> action.updateState {
                it.copy(ownPlayStyle = action.playStyle)
            }
            is PlayPokerAction.OwnsOpponentStyleReaderChanged -> action.updateState {
                it.copy(ownsOpponentStyleReader = action.owned)
            }
            is PlayPokerAction.RequestOpponentStyle -> {
                // Fetch once per opponent per session. Only a *successful* fetch
                // is cached — a transient network failure leaves the key absent
                // so reopening the card retries instead of permanently showing
                // "no style". A genuine empty (sampleSize 0) is a success and is
                // cached, so a sparse opponent isn't refetched on every open.
                if (action.userId !in stateFlow.value.opponentStyles) {
                    viewModelScope.launch {
                        playStyleRepository.getStyleFor(action.userId)
                            .onSuccess {
                                takeAction(PlayPokerAction.OpponentStyleLoaded(action.userId, it))
                            }
                            .onFailure {
                                logger.w(it) { "Opponent style fetch failed for ${action.userId}" }
                            }
                    }
                }
            }
            is PlayPokerAction.OpponentStyleLoaded -> action.updateState {
                it.copy(opponentStyles = it.opponentStyles + (action.userId to action.playStyle))
            }
            is PlayPokerAction.AddFriend -> {
                // Optimistic flip to Sent, un-flipped only on a server reject —
                // same model as Home's recently-played add-friend tile. The fetch
                // runs on its own launch so the round-trip never stalls the action
                // loop. A successful or auto-accepted request stays Sent.
                if (action.userId !in requestedFriendIds) {
                    requestedFriendIds += action.userId
                    action.updateState {
                        it.copy(friendRequestSentIds = it.friendRequestSentIds + action.userId)
                    }
                    viewModelScope.launch {
                        val stuck = when (friendRepository.sendRequest(action.userId)) {
                            is SendFriendRequestResult.Requested,
                            is SendFriendRequestResult.Accepted -> true
                            else -> false
                        }
                        if (!stuck) takeAction(PlayPokerAction.FriendRequestFailed(action.userId))
                    }
                }
            }
            is PlayPokerAction.FriendRequestFailed -> {
                requestedFriendIds -= action.userId
                action.updateState {
                    it.copy(friendRequestSentIds = it.friendRequestSentIds - action.userId)
                }
            }
        }
    }

}
