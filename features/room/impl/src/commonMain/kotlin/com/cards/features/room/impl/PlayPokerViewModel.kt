package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.IntentRejectedException
import com.dangerfield.cards.features.room.impl.session.IntentTimeoutException
import com.dangerfield.cards.features.room.impl.session.NextHandRefusal
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
import com.dangerfield.cards.libraries.cards.GameSpeed
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
import com.dangerfield.cards.libraries.core.logging.logEvent
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
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.ui.components.resolvePlayerBadges
import com.dangerfield.cards.libraries.ui.components.poker.EquippedFelt
import com.dangerfield.cards.libraries.ui.components.poker.badgeEmojiForProductId
import com.dangerfield.cards.libraries.ui.components.poker.cardBackForProductId
import com.dangerfield.cards.libraries.ui.components.poker.feltForProductId
import com.dangerfield.cards.libraries.review.ReviewPromptCoordinator
import com.dangerfield.cards.libraries.review.ReviewTrigger
import com.dangerfield.cards.libraries.social.FriendRepository
import com.dangerfield.cards.libraries.social.ReportPlayerResult
import com.dangerfield.cards.libraries.social.ReportRepository
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
import kotlin.time.TimeSource

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
    private val reportRepository: ReportRepository,
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
    private var latestGameSpeed: GameSpeed = GameSpeed.Normal

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

    // Hand number of the last hand we ran the full hand-end credit path for
    // (XP award + achievement reveal + feedback). Solo fires onHandEnded exactly
    // once per hand, but the MP event flow can re-deliver a HandEnded (replay /
    // resync), which would otherwise double-award XP and re-fire the celebration.
    private var lastCreditedHand: Int? = null

    // Running session win-loss tally, surfaced in the "…" overflow sheet. Bumped
    // once per finished hand the human played (guarded by lastCreditedHand, which
    // gates handleHandEnded against re-delivered HandEnded events).
    private var sessionHandsWon: Int = 0
    private var sessionHandsLost: Int = 0

    // A rare-achievement / level-up review ask stashed at hand-end because a
    // celebration sheet is about to reveal, so the OS prompt doesn't step on it.
    // Flushed when the sheet is dismissed (CelebrationDismissed).
    private var pendingReviewTrigger: ReviewTrigger? = null

    // game.started → game.ended bracketing for the app-event funnel. Multiple
    // end paths can race (leave + room close); the latch keeps it one per session.
    private val sessionStartedAt = TimeSource.Monotonic.markNow()
    private var gameEndLogged = false
    private var quickBuyUsedThisSession = false

    // Hand number of the last hole-card render projection we logged (GAME-8).
    // Guards the once-per-hand "what the table projected for my seat" line so it
    // fires once cards are dealt, never per snapshot/frame.
    private var lastLoggedHoleCardHand: Int? = null

    // Opponents we've already fired a friend request at this session — guards a
    // double-tap from sending twice (the inline button also flips to Sent).
    private val requestedFriendIds: MutableSet<String> = mutableSetOf()

    // Opponents we've already filed a report against this session — guards a
    // double-tap from filing twice (the inline button also flips to Reported).
    private val reportedIds: MutableSet<String> = mutableSetOf()

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
        gameSpeedProvider = { latestGameSpeed },
        onHandEnded = { event, state, humanStartingStack ->
            handleHandEnded(event, state, humanStartingStack)
        },
    )

    init {
        logger.logEvent(
            "game.started",
            "mode" to sessionFactory.xpMode.name.lowercase(),
            "difficulty" to sessionFactory.difficultyName,
        )
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
        // from a transient drop); the entry point pops the screen. A heads-up
        // match-over (MP-14) is terminal too, but routes through a result overlay
        // first instead of a silent pop — resolve the win/loss role here and surface
        // it; the screen routes off when the player dismisses the result.
        viewModelScope.launch {
            session.roomClosed.collect { reason ->
                // A terminal close is an auto-end: the server has cashed the
                // finished stack back to the wallet, so reconcile here too — the
                // user never tapped Leave (MP-21). On appScope so it lands even as
                // the screen pops.
                appScope.launch { reconcileWalletAfterGame() }
                when (reason) {
                    is ClosedReason.MatchOver -> {
                        logGameEnded("match_over")
                        takeAction(
                            PlayPokerAction.MatchOverResolved(
                                localPlayerWon = reason.winnerUserId == localPlayerId(),
                            ),
                        )
                    }
                    else -> {
                        logGameEnded("room_closed")
                        sendEvent(PlayPokerEvent.RoomClosed(reason))
                    }
                }
            }
        }
        // Last human standing — distinct from roomClosed (the room still exists);
        // the entry point routes by room kind. Never fires for solo bots.
        viewModelScope.launch {
            session.opponentsLeft.collect {
                // Auto-end: reconcile the settled wallet before the entry point
                // routes Home, so the balance isn't stale until the next
                // foreground (MP-21 / CARDS-4B). On appScope so it survives the pop.
                appScope.launch { reconcileWalletAfterGame() }
                logGameEnded("opponent_left")
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
        // Server refused the next hand — split the genuine can't-deal case (the
        // winner waits on the rebuy-grace countdown) from a transient resync race
        // so a backgrounded-then-stale tap never gets the terminal rebuy copy
        // (MP-22). Never fires for solo bots.
        viewModelScope.launch {
            session.nextHandRefused.collect { refusal ->
                val event = when (refusal) {
                    NextHandRefusal.CannotDeal -> PlayPokerEvent.NextHandUnavailable
                    NextHandRefusal.Transient -> PlayPokerEvent.NextHandResyncing
                }
                // Info: which user-facing event a refusal mapped to — the wrong
                // mapping (every refusal → rebuy toast) was invisible from the
                // ack log alone (MP-22). Once per refusal, never in a loop.
                logger.i { "next-hand refusal $refusal → ${event::class.simpleName}" }
                sendEvent(event)
            }
        }
        // Heads-up match-over grace countdown (MP-14) → on-table banner. Opens on
        // a bust dead-end, clears on a rebuy; a terminal expiry routes off via
        // roomClosed above. Never fires for solo bots.
        viewModelScope.launch {
            session.matchOverCountdown.collect { countdown ->
                takeAction(PlayPokerAction.MatchOverCountdownChanged(countdown))
            }
        }
        // Between-hands auto-advance countdown → on-felt "Next hand in 0:0X". Opens
        // when the server holds the next deal, clears when it deals. The screen only
        // renders it on real-chip tables (the leave-with-winnings window); practice
        // keeps its result dialog. Never fires for solo bots.
        viewModelScope.launch {
            session.nextHandCountdown.collect { countdown ->
                takeAction(PlayPokerAction.NextHandCountdownChanged(countdown))
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
                latestGameSpeed = data.gameSpeed
                takeAction(PlayPokerAction.TurnFeedbackChanged(data.turnFeedback))
                takeAction(PlayPokerAction.GameSpeedChanged(data.gameSpeed))
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
        // Equipped cosmetics → mid-session repaint, combined with the host's
        // table-wide cosmetics (SHOP-3). The flow is newest-first, so pick the first
        // non-Default per slot. Felt + card back honour the host's table choice when
        // the room sets one, falling back to the player's own equipped cosmetic;
        // the win-odds tool + badge stay purely the local player's. Also drives the
        // win-odds tool flag.
        viewModelScope.launch {
            combine(
                equipmentRepository.observeEquipped(),
                session.tableCosmetics,
            ) { entries, table ->
                val ownFelt = entries
                    .map { feltForProductId(it.productId) }
                    .firstOrNull { it != EquippedFelt.Default }
                    ?: EquippedFelt.Default
                val ownCardBack = entries
                    .map { cardBackForProductId(it.productId) }
                    .firstOrNull { it != com.dangerfield.cards.libraries.ui.components.poker.CardBackStyle.Default }
                    ?: com.dangerfield.cards.libraries.ui.components.poker.CardBackStyle.Default
                val felt = table?.feltProductId?.let { feltForProductId(it) } ?: ownFelt
                val cardBack = table?.cardBackProductId?.let { cardBackForProductId(it) } ?: ownCardBack
                ResolvedCosmetics(
                    felt = felt,
                    cardBack = cardBack,
                    winOddsTool = entries.any { it.productId == TOOL_WIN_ODDS_PRODUCT_ID },
                    badgeEmoji = entries.firstNotNullOfOrNull { badgeEmojiForProductId(it.productId) },
                )
            }.collect { resolved ->
                takeAction(PlayPokerAction.EquippedFeltChanged(resolved.felt))
                takeAction(PlayPokerAction.EquippedCardBackChanged(resolved.cardBack))
                takeAction(PlayPokerAction.WinOddsToolEquippedChanged(resolved.winOddsTool))
                takeAction(PlayPokerAction.EquippedBadgeChanged(resolved.badgeEmoji))
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

    /**
     * Once per hand (GAME-8), log what the table actually projected for the human
     * seat: dealt-cards count vs face-up-rendered count. A "cards didn't show"
     * report (CARDS-style) is otherwise undiagnosable from logs — the session log
     * is engine-level only, so it never reveals whether the *view* received cards
     * it could render face-up. Fires only once the human's cards have been dealt,
     * keyed on `handNumber` so it's one line per hand, never per snapshot/frame.
     */
    private fun logHoleCardProjection(table: TableUiState) {
        val active = table as? TableUiState.Active ?: return
        val human = active.seats.firstOrNull { it.isHuman } ?: return
        val dealt = human.holeCards.size
        if (dealt == 0 || active.handNumber == lastLoggedHoleCardHand) return
        lastLoggedHoleCardHand = active.handNumber
        // Face-up = cards the projection would render face-up (not as backs). The
        // human always sees their own cards, so backs here means the projection
        // withheld them — the smoking gun for a "can't see my hand" report.
        val faceUp = if (human.showHoleCardBacks) 0 else dealt
        logger.i {
            "hole-card projection hand=${active.handNumber} dealt=$dealt faceUp=$faceUp"
        }
    }

    /**
     * The fold to auto-submit for an armed pre-fold (GAME-30), or null when there's
     * nothing to fire yet. Pure: returns the fold only once it's the human's turn —
     * a pre-fold always folds on turn arrival, no matter what the action did while
     * they waited (unlike a conditional check, it never checks or disarms itself).
     * The caller clears [PlayPokerState.preFoldArmed] when this fires.
     */
    private fun preFoldToFire(state: PlayPokerState): PlayerIntent? {
        if (!state.preFoldArmed) return null
        val active = state.table as? TableUiState.Active ?: return null
        if (!active.isHumanTurn) return null
        val human = active.seats.firstOrNull { it.isHuman } ?: return null
        return PlayerIntent.Fold(human.index)
    }

    private fun handleHandEnded(
        event: GameEvent.HandEnded,
        state: GameState,
        humanStartingStack: Long,
    ) {
        // Credit each hand at most once even if HandEnded is re-delivered (the
        // MP event flow replays). Without this a resync double-awards XP and
        // re-fires the celebration. Solo never re-delivers, so it's a no-op there.
        if (state.handNumber == lastCreditedHand) return
        lastCreditedHand = state.handNumber
        // Resolve the human's seat from live state (MP seats vary) so the hand
        // is attributed to the right player.
        val humanSeatIndex = sessionFactory.humanSeatIndex(state)
        val summary = HandResultSummaryBuilder.build(
            event = event,
            state = state,
            humanSeatIndex = humanSeatIndex,
            mode = sessionFactory.xpMode,
        )
        // Session win-loss tally for the overflow sheet. Count only hands the human
        // actually played (a seatless spectator/joiner produces a no-participation
        // summary); a win is a pot taken, everything else dealt-in is a loss.
        val participated = summary.wasFold || summary.reachedShowdown ||
            summary.wonPot || summary.chipsCommitted > 0
        if (participated) {
            logger.logEvent(
                "hand.completed",
                "mode" to sessionFactory.xpMode.name.lowercase(),
                "hand_number" to state.handNumber,
                "won" to summary.wonPot,
                "showdown" to summary.reachedShowdown,
            )
            if (summary.wonPot) sessionHandsWon += 1 else sessionHandsLost += 1
            takeAction(PlayPokerAction.SessionRecordChanged(sessionHandsWon, sessionHandsLost))
        }
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
            val showPopups = appCache.get().showAchievementPopups
            earned.forEach {
                logger.logEvent(
                    "achievement.celebration_shown",
                    "achievement_id" to it.achievement.id.name.lowercase(),
                    "rarity" to it.achievement.rarity.name.lowercase(),
                    "silenced" to !showPopups,
                )
            }
            val surfaced = if (showPopups) earned else emptyList()
            takeAction(PlayPokerAction.AchievementsEarned(surfaced))
            enqueueUnsurfacedMpUnlocks(surfaced, context)

            // The review prompt keys off the *real* unlocks, not what we showed —
            // a silenced celebration shouldn't also suppress a review ask. When a
            // celebration sheet is about to reveal (bots + surfaced unlocks) the
            // ask is stashed and fired on dismissal so it doesn't step on it.
            val celebrationWillShow =
                sessionFactory.xpMode == XpMode.BOTS && surfaced.isNotEmpty()
            maybeRequestReviewPrompt(
                priorLevel = priorLevel,
                earned = earned,
                celebrationWillShow = celebrationWillShow,
            )
        }
    }

    /**
     * Bank achievements earned in a real-chip multiplayer hand that had no
     * in-game surface to reveal them, so Home can celebrate them on return
     * (PROG-13). A finished real-chip hand shows its result on the felt (with the
     * leave-with-winnings countdown), not in a dialog, so the at-table reveal
     * that solo/practice get never fires. A bust is the one real-chip case with
     * an inline surface — the [MultiplayerBustDialog] already shows the unlocks —
     * so those are skipped here to avoid a double celebration. [surfaced] already
     * honours the "silence pop-ups" setting, so a silenced session enqueues
     * nothing, matching the muted at-table behaviour.
     */
    private suspend fun enqueueUnsurfacedMpUnlocks(
        surfaced: List<EarnedAchievement>,
        context: AchievementHandContext,
    ) {
        if (surfaced.isEmpty()) return
        if (!stateFlow.value.realChipsAtStake) return
        val humanBusted = context.humanEndingStack <= 0L
        if (humanBusted) return
        val ids = surfaced.map { it.achievement.id.name }
        logger.logEvent(
            "achievement.home_celebration_enqueued",
            "count" to ids.size,
        )
        appCache.update { data ->
            data.copy(
                pendingHomeAchievementIds = (data.pendingHomeAchievementIds + ids).distinct(),
            )
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

    private suspend fun requestReviewPrompt(trigger: ReviewTrigger) {
        Catching {
            reviewPromptCoordinator.requestPrompt(trigger)
        }.onFailure { logger.w(it) { "Review prompt request failed for $trigger" } }
    }

    private suspend fun maybeRequestReviewPrompt(
        priorLevel: Int?,
        earned: List<EarnedAchievement>,
        celebrationWillShow: Boolean,
    ) {
        Catching {
            val trigger = resolveReviewTrigger(priorLevel, earned) ?: return@Catching
            if (celebrationWillShow) {
                pendingReviewTrigger = trigger
            } else {
                requestReviewPrompt(trigger)
            }
        }.onFailure { logger.w(it) { "Review prompt request failed" } }
    }

    private suspend fun resolveReviewTrigger(
        priorLevel: Int?,
        earned: List<EarnedAchievement>,
    ): ReviewTrigger? {
        val unlockedRareOrBetter = earned.any {
            it.achievement.rarity.ordinal >= AchievementRarity.RARE.ordinal
        }
        if (unlockedRareOrBetter) return ReviewTrigger.AchievementUnlocked
        if (priorLevel != null) {
            val newLevel = levelProgressFor(
                progressionRepository.getProgression().totalXp,
                levelCurve,
            ).level
            if (newLevel > priorLevel) {
                sendEvent(PlayPokerEvent.PlayHaptic(HapticKind.LevelUp))
                return ReviewTrigger.LevelUp
            }
        }
        return null
    }

    /**
     * The local player's user id, resolved from the last game state via the
     * factory's seat mapping. Used to decide the match-over win/loss role (MP-14).
     * Null if the local seat hasn't resolved yet (no snapshot, or seatless joiner).
     */
    private fun localPlayerId(): String? {
        val state = lastGameState ?: return null
        val seatIndex = sessionFactory.humanSeatIndex(state)
        return state.seats.firstOrNull { it.index == seatIndex }?.playerId
    }

    private fun logGameEnded(endReason: String) {
        if (gameEndLogged) return
        gameEndLogged = true
        logger.logEvent(
            "game.ended",
            "mode" to sessionFactory.xpMode.name.lowercase(),
            "hands_played" to sessionHandsWon + sessionHandsLost,
            "duration_sec" to sessionStartedAt.elapsedNow().inWholeSeconds,
            "end_reason" to endReason,
        )
    }

    private fun PlayerIntent.eventName(): String = this::class.simpleName?.lowercase() ?: "unknown"

    // Latches the user-initiated leave teardown (server leave + wallet
    // reconcile) so it runs at most once. Two leave paths can both fire: the
    // screen's BackHandler fires LeaveTable, and an iOS edge-swipe that bypasses
    // Compose's BackHandler reaches the entry point's onBack, which also fires
    // LeaveTable so the swipe-back still reconciles (MP-23 / CARDS-5B). A second
    // session.leave is a redundant DELETE; a second reconcile is guarded
    // separately by walletReconciled, but latching here keeps the whole teardown
    // single-shot.
    private var leaveInitiated = false

    private suspend fun leaveAndReconcileWallet() {
        if (leaveInitiated) return
        leaveInitiated = true
        // The leave call cashes out synchronously and returns the authoritative
        // post-settlement balance (MP-29); apply it directly so the wallet
        // reconciles off the leave itself, no speculative sync racing the
        // server's settlement commit. A null (deferral / failure / solo) falls
        // back to a sync inside reconcileWalletAfterGame.
        val settledBalance = Catching { session.leave() }
            .onFailure { e -> logger.w(e) { "room leave failed" } }
            .getOrNull()
        reconcileWalletAfterGame(settledBalance)
    }

    // Guards the leave-cash-out *confirmation* toast to one per session-end. A
    // user-initiated leave and an auto-terminal signal can both reconcile (e.g.
    // the player taps Leave as the room closes); the reconcile itself is now
    // idempotent + retry-safe (MP-29 dropped the single-shot sync latch), but the
    // credit toast must still fire at most once so a second reconcile doesn't
    // re-confirm a now-zero delta or double a real one.
    private var creditConfirmed = false

    /**
     * Reconcile the wallet after the table ends — whether the player left, busted
     * out, the last opponent left, the heads-up match resolved, or the room
     * closed. On a real-chip table the server cashes the finished stack back to
     * the wallet.
     *
     * When a voluntary leave already carried the authoritative post-settlement
     * balance ([settledBalance], MP-29), we apply it directly via [setBalance] —
     * the leave call *was* the reconcile, so there's no speculative sync racing
     * the server's settlement commit (the CARDS-5R / 3E cluster). Otherwise (an
     * involuntary teardown whose settled balance arrives over the socket, a
     * deferred all-in settlement, or a leave that never reached the server) we
     * fall back to a [sync], which now also *retries* — the single-shot latch is
     * gone, so a first sync that lost the race no longer strands the balance
     * stale until the next foreground (CARDS-3C / CARDS-4B). No-op for solo bots.
     */
    private suspend fun reconcileWalletAfterGame(settledBalance: Long? = null) {
        if (sessionFactory.xpMode != XpMode.MULTIPLAYER) return

        val balanceBefore = chipsRepository.getBalance()
        if (settledBalance != null) {
            Catching { chipsRepository.setBalance(settledBalance) }
                .onFailure { e -> logger.w(e) { "applying settled balance after leave failed" } }
        } else {
            Catching { chipsRepository.sync() }
                .onFailure { e -> logger.w(e) { "wallet sync after game-end failed" } }
        }
        // MP-6: the reconcile above lands the credited stack in the balance, but
        // a silent number change reads as a glitch. Confirm the credit on the
        // surface the player lands on so the wallet bump never surprises them
        // (Sentry CARDS-2N / 2Y). Only fire on a real gain — a lost stack or
        // empty leave stays quiet.
        val balanceAfter = chipsRepository.getBalance()
        if (balanceBefore == null || balanceAfter == null) return
        val credited = balanceAfter - balanceBefore
        if (credited > 0L && !creditConfirmed) {
            creditConfirmed = true
            leaveCashOutNotifier.confirmCredit(credited = credited, balanceAfter = balanceAfter)
        }
    }

    override suspend fun handleAction(action: PlayPokerAction) {
        when (action) {
            is PlayPokerAction.GameStateUpdated -> {
                lastGameState = action.state
                var projected: TableUiState? = null
                var preActionToFire: PlayerIntent? = null
                action.updateState { current ->
                    // A pre-fold and the per-seat action pills both belong to the
                    // hand they happened in. Retire them on a hand change off the
                    // authoritative snapshot hand number, so a stale arm can't fold
                    // the fresh deal and last hand's "Folded" badge doesn't linger
                    // — the HandStarted event and the new-hand snapshot ride
                    // unordered flows, so the event-driven clear can lose the race
                    // (MP especially; GAME-33).
                    val prevHand = (current.table as? TableUiState.Active)?.handNumber
                    val newHand = action.state.handNumber
                    val handChanged = prevHand != null && newHand != prevHand
                    if (handChanged) lastActionBySeat.clear()
                    val table = sessionFactory.tableFor(
                        state = action.state,
                        lastWinners = lastWinners,
                        lastActionBySeat = lastActionBySeat.toMap(),
                        humanProfile = latestHumanProfile,
                        humanLevel = current.humanLevel,
                        curve = levelCurve,
                    )
                    projected = table
                    val withTable = current.copy(
                        table = table,
                        preFoldArmed = if (handChanged) false else current.preFoldArmed,
                    )
                    val fold = preFoldToFire(withTable)
                    preActionToFire = fold
                    if (fold != null) withTable.copy(preFoldArmed = false) else withTable
                }
                projected?.let { logHoleCardProjection(it) }
                preActionToFire?.let { takeAction(PlayPokerAction.Submit(it)) }
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
                    is GameEvent.StreetAdvanced -> {
                        // Street pills reset, but a fold stays legible for the
                        // rest of the hand — seat cue + player-card "last move"
                        // must still explain the greyed seat (GAME-17).
                        lastActionBySeat.entries.removeAll { it.value !is PlayerAction.Fold }
                        true
                    }
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
                                // Clear the dedupe token so a corrected resubmit goes
                                // through, and surface a transient hint — a timeout
                                // ("didn't send") or a rejection ("not allowed") would
                                // otherwise be a dead pause then silence (MP-20).
                                if (submittedTurnToken == turnToken) submittedTurnToken = null
                                when (e) {
                                    is IntentTimeoutException -> {
                                        logger.logEvent("game.intent_timeout", "intent_type" to action.intent.eventName())
                                        sendEvent(PlayPokerEvent.IntentFeedback(IntentFeedbackKind.TimedOut))
                                    }
                                    is IntentRejectedException -> {
                                        logger.logEvent("game.intent_rejected", "intent_type" to action.intent.eventName())
                                        sendEvent(PlayPokerEvent.IntentFeedback(IntentFeedbackKind.Rejected))
                                    }
                                    else -> Unit
                                }
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
            is PlayPokerAction.SetPreFold -> {
                // Arming while it's already the human's turn (a race where the
                // control is tapped as the turn lands) folds straight away; the
                // usual case just stores the arm for the next turn arrival.
                var preActionToFire: PlayerIntent? = null
                action.updateState { current ->
                    val armed = current.copy(preFoldArmed = action.armed)
                    val fold = preFoldToFire(armed)
                    preActionToFire = fold
                    if (fold != null) armed.copy(preFoldArmed = false) else armed
                }
                preActionToFire?.let { takeAction(PlayPokerAction.Submit(it)) }
            }

            is PlayPokerAction.ToggleCheatSheet -> action.updateState {
                it.copy(cheatSheetOpen = !it.cheatSheetOpen)
            }
            is PlayPokerAction.DismissEarnedToast -> action.updateState {
                it.copy(recentlyEarned = emptyList())
            }
            is PlayPokerAction.CelebrationDismissed -> {
                pendingReviewTrigger?.let { trigger ->
                    pendingReviewTrigger = null
                    requestReviewPrompt(trigger)
                }
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
            is PlayPokerAction.GameSpeedChanged -> action.updateState {
                it.copy(gameSpeed = action.value)
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
            is PlayPokerAction.MatchOverCountdownChanged -> action.updateState {
                it.copy(matchOverCountdown = action.countdown)
            }
            is PlayPokerAction.NextHandCountdownChanged -> action.updateState {
                it.copy(nextHandCountdown = action.countdown)
            }
            is PlayPokerAction.SessionRecordChanged -> action.updateState {
                it.copy(sessionHandsWon = action.won, sessionHandsLost = action.lost)
            }
            is PlayPokerAction.LeaveTable -> {
                // Only ask at a genuine peak: a bot session the player ended up
                // on (more hands won than lost). A losing grind that quits out is
                // not a positive moment, so it never triggers the prompt.
                if (sessionFactory.xpMode == XpMode.BOTS && sessionHandsWon > sessionHandsLost) {
                    requestReviewPrompt(ReviewTrigger.SessionEnd)
                }
                logGameEnded("left")
                // On appScope, not viewModelScope: the screen pops this VM the
                // instant it fires LeaveTable, but the leave must still reach the
                // server. No-op for solo.
                appScope.launch { leaveAndReconcileWallet() }
            }
            is PlayPokerAction.LeaveGameFromBust -> {
                logGameEnded("bust")
                // Same teardown as LeaveTable, on appScope so it lands as the
                // screen routes away.
                appScope.launch { leaveAndReconcileWallet() }
            }
            is PlayPokerAction.MatchOverResolved -> {
                // Winning a real-chip multiplayer match is the strongest positive
                // moment we have — ask for a review here (bots-mode SessionEnd
                // never covered MP wins).
                if (action.localPlayerWon && state.isRealMultiplayer) {
                    requestReviewPrompt(ReviewTrigger.MultiplayerWin)
                }
                // The match ended — surface the result and drop the now-stale
                // countdown. The screen routes off (firing LeaveGameFromBust) when
                // the player dismisses the result overlay.
                action.updateState {
                    it.copy(
                        matchOverResult = MatchOverResult(localPlayerWon = action.localPlayerWon),
                        matchOverCountdown = null,
                    )
                }
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
                        quickBuyUsedThisSession = true
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
                        .onSuccess {
                            logger.logEvent(
                                "game.rebuy",
                                "mode" to sessionFactory.xpMode.name.lowercase(),
                                "via_quick_buy" to quickBuyUsedThisSession,
                            )
                            sendEvent(PlayPokerEvent.RebuySucceeded)
                        }
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
                logger.logEvent("emote.sent", "mode" to sessionFactory.xpMode.name.lowercase())
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
                if (action.key !in stateFlow.value.mutedEmojiPlayerKeys) {
                    logger.logEvent("emote.player_muted")
                }
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
            is PlayPokerAction.ReportPlayer -> {
                // Optimistic flip to Reported, un-flipped only if the server
                // rejects — mirrors the add-friend model. The report round-trips
                // on its own launch so it never stalls the action loop. Reporting
                // is fire-and-forget: on success the screen toasts a confirmation,
                // on failure it toasts a retry hint and the flip reverts.
                if (action.userId !in reportedIds) {
                    reportedIds += action.userId
                    action.updateState {
                        it.copy(reportedUserIds = it.reportedUserIds + action.userId)
                    }
                    viewModelScope.launch {
                        when (
                            reportRepository.reportPlayer(
                                userId = action.userId,
                                roomCode = stateFlow.value.roomCode,
                                reason = action.reason,
                                categories = action.categories,
                            )
                        ) {
                            is ReportPlayerResult.Reported -> sendEvent(PlayPokerEvent.PlayerReported)
                            is ReportPlayerResult.RateLimited -> {
                                takeAction(PlayPokerAction.ReportPlayerFailed(action.userId))
                                sendEvent(PlayPokerEvent.PlayerReportFailed(rateLimited = true))
                            }
                            is ReportPlayerResult.Error -> {
                                takeAction(PlayPokerAction.ReportPlayerFailed(action.userId))
                                sendEvent(PlayPokerEvent.PlayerReportFailed(rateLimited = false))
                            }
                        }
                    }
                }
            }
            is PlayPokerAction.ReportPlayerFailed -> {
                reportedIds -= action.userId
                action.updateState {
                    it.copy(reportedUserIds = it.reportedUserIds - action.userId)
                }
            }
        }
    }

}

/**
 * The cosmetics painted on the play surface this emission — the host's table-wide
 * felt + card back (SHOP-3) when the room sets them, else the local player's own
 * equipped cosmetic, plus the local player's win-odds tool + badge (never
 * table-wide). Lifted to a value type so the felt + card-back + tool + badge land
 * in a single combined emission rather than four racing collectors.
 */
private data class ResolvedCosmetics(
    val felt: EquippedFelt,
    val cardBack: com.dangerfield.cards.libraries.ui.components.poker.CardBackStyle,
    val winOddsTool: Boolean,
    val badgeEmoji: String?,
)
