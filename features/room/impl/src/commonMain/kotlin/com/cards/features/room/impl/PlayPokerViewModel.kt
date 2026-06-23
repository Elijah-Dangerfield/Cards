package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.PokerSession
import com.dangerfield.cards.features.room.impl.session.PokerSessionFactory
import com.dangerfield.cards.features.room.impl.usecase.EmoteGate
import com.dangerfield.cards.features.room.impl.usecase.HandEndProgression
import com.dangerfield.cards.features.room.impl.usecase.HandResultSummaryBuilder
import com.dangerfield.cards.features.room.impl.usecase.WinOddsEngine

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AchievementRarity
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.BotSpeed
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.EmojiBlast
import com.dangerfield.cards.libraries.cards.EmotePackCatalog
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.InventoryRepository
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
    private val progressionConfig: ProgressionConfig,
    private val achievementRepository: AchievementRepository,
    private val appCache: AppCache,
    private val equipmentRepository: EquipmentRepository,
    private val inventoryRepository: InventoryRepository,
    private val productsRepository: ProductsRepository,
    private val profileRepository: ProfileRepository,
    private val reviewPromptCoordinator: ReviewPromptCoordinator,
    private val dispatcherProvider: DispatcherProvider,
    private val appScope: AppCoroutineScope,
    private val clock: Clock,
) : SEAViewModel<PlayPokerState, PlayPokerEvent, PlayPokerAction>(
    initialStateArg = PlayPokerState(xpMode = sessionFactory.xpMode),
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
        // Owned emote-pack IDs → blast-tray pool (empty hides the tray).
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
        // Catalog in state so the screen can resolve opponents' badge ids.
        viewModelScope.launch {
            productsRepository.observeCatalog().collect { catalog ->
                takeAction(PlayPokerAction.CatalogChanged(catalog))
            }
        }
        viewModelScope.launch { productsRepository.refresh() }
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
                    levelCurve,
                ).level
                if (newLevel > priorLevel) {
                    sendEvent(PlayPokerEvent.PlayHaptic(HapticKind.LevelUp))
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
                            curve = levelCurve,
                        ),
                    )
                }
            }
            is PlayPokerAction.OccupantsUpdated -> action.updateState {
                it.copy(occupants = action.occupants)
            }
            is PlayPokerAction.GameEventReceived -> {
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
                appScope.launch {
                    Catching { session.leave() }
                        .onFailure { e -> logger.w(e) { "room leave failed" } }
                }
            }
            is PlayPokerAction.LeaveGameFromBust -> {
                // Same teardown as LeaveTable, on appScope so it lands as the
                // screen routes away.
                appScope.launch {
                    Catching { session.leave() }
                        .onFailure { e -> logger.w(e) { "room leave failed" } }
                }
            }
            is PlayPokerAction.BuyChips -> sendEvent(PlayPokerEvent.NavigateToShop)
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
        }
    }

}
