package com.dangerfield.cards.features.room.impl

// TopBar icons route through libraries/ui's DS Icon + IconButton (Icons.kt
// / IconButton.kt). Raw Material icons would tint, size, and bounce-click
// differently from the rest of the app — DS routing keeps the chrome
// consistent and the icon set centralized.
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_connection_lost_banner
import cards.libraries.resources.generated.resources.room_loading_dealing_in
import cards.libraries.resources.generated.resources.room_practice_tier_bots_present
import cards.libraries.resources.generated.resources.room_top_bar_back_a11y
import cards.libraries.resources.generated.resources.room_top_bar_hand_info_a11y
import com.dangerfield.cards.libraries.bots.EquityBreakdown
import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandEvaluator
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.BadgeDetailSheet
import com.dangerfield.cards.libraries.ui.components.LevelPill
import com.dangerfield.cards.libraries.ui.components.PlayerBadge
import com.dangerfield.cards.libraries.ui.components.resolvePlayerBadges
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.poker.EmojiBlastOverlay
import com.dangerfield.cards.libraries.ui.components.poker.EquippedFelt
import com.dangerfield.cards.libraries.ui.components.poker.LocalCardBackStyle
import com.dangerfield.cards.libraries.ui.components.poker.LocalFeltAccentSurface
import com.dangerfield.cards.libraries.ui.components.poker.feltAccentSurface
import com.dangerfield.cards.libraries.ui.components.poker.feltSurfaceColor
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.HorizontalSpacerD100
import com.dangerfield.cards.system.HorizontalSpacerD200
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD700
import com.dangerfield.cards.system.VerticalSpacerD800
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview


@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayPokerScreen(
    state: PlayPokerState,
    onAction: (PlayPokerAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onTapXp: () -> Unit = {},
    /** Hides the centered Level pill in the top bar. The tutorial sets
     *  this false so its own step-counter pill can occupy the centered
     *  slot without colliding. */
    showXpPill: Boolean = true,
    /** Optional content for the centered top-bar slot, rendered in the
     *  exact spot the Level pill occupies. The tutorial passes its
     *  step-counter pill here so it sits *in* the top bar (vertically
     *  centered with the back chevron) rather than floating above it.
     *  Takes precedence over the Level pill when non-null. */
    topBarCenterSlot: (@Composable () -> Unit)? = null,
    /** When false, the back button leaves immediately instead of opening
     *  the "you'll lose this hand" confirm dialog. Tutorial sets this
     *  false because there's no real hand or XP at stake. */
    confirmLeave: Boolean = true,
) {
    var actionSheetOpen by remember { mutableStateOf(false) }
    var blindExplainerOpen by remember { mutableStateOf(false) }
    var potExplainerOpen by remember { mutableStateOf(false) }
    var stackExplainerOpen by remember { mutableStateOf(false) }
    var leaveConfirmOpen by remember { mutableStateOf(false) }
    var swipeFoldConfirmOpen by remember { mutableStateOf(false) }
    var profileSheetSeat by remember { mutableStateOf<SeatView?>(null) }
    var selfCardOpen by remember { mutableStateOf(false) }
    // A badge/title chip tapped on the player-profile sheet — opens its
    // read-about-it detail sheet.
    var selectedBadge by remember { mutableStateOf<PlayerBadge?>(null) }
    // Action / bet / hand-label explainers carry their own context so each
    // dialog can render specific copy instead of opening the whole cheat sheet.
    var lastActionDialog by remember { mutableStateOf<Pair<String, PlayerAction>?>(null) }
    var betPillDialog by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var handLabelDialog by remember { mutableStateOf<String?>(null) }
    // Achievement-celebration sequencing — declared up here so the displayed-XP
    // gate below can freeze the LevelPill while either the hand-result dialog
    // or the celebration sheet is on screen.
    var celebrationActive by remember { mutableStateOf(false) }
    // Set when the player dismisses the end-of-hand dialog while achievement
    // computation is still in flight (a fast tap). We hold the advance instead
    // of skipping a reveal that hasn't been computed yet; the effect below
    // resolves it the moment recordHand lands.
    var advanceRequested by remember { mutableStateOf(false) }
    val active = state.table as? TableUiState.Active
    // The hand-result dialog and (in bot mode) the celebration sheet both
    // overlay the top-bar LevelPill, so any XP earned by this hand animates
    // behind the scrim — the user never sees the ring fill. Hold the pill at
    // its pre-hand value while either surface is visible; release once both
    // dismiss so `animateFloatAsState` inside `LevelPill` can play the
    // progress-ring change against an uncovered top bar.
    val handResultOverlaying = active?.handResult != null
    val xpFrozen = handResultOverlaying || celebrationActive
    var displayedXp by remember { mutableStateOf(state.xp) }
    LaunchedEffect(state.xp, xpFrozen) {
        if (!xpFrozen) displayedXp = state.xp
    }
    // Mirror of the XP-deferral gate for the human's chip stack. The chip
    // tile sits below the dialog/sheet scrim during the win-celebration
    // moment, so a naive snap from old-stack to new-stack happens behind
    // the overlay and the user never sees the count-up. Holding the
    // displayed value at its pre-hand number until both overlays dismiss
    // lets `AnimatedNumberText` (inside `ChipCoinAmount(animated = true)`)
    // play the odometer roll against an uncovered tile.
    val humanStack = active?.seats?.firstOrNull { it.isHuman }?.stack ?: 0L
    var displayedHumanStack by remember { mutableStateOf(humanStack) }
    LaunchedEffect(humanStack, xpFrozen) {
        if (!xpFrozen) displayedHumanStack = humanStack
    }
    // Hand-end reward particles. When the gate above releases (both overlays
    // dismissed), fly an XP badge up to the LevelPill and — if the hand was a
    // win — a coin down to the chip stack, so the freshly-unfrozen ring fill
    // and stack count-up read as "this is where that reward landed". Anchors
    // are published by the LevelPill / chip-stack call sites into this holder;
    // the overlay reads them when it fires.
    val rewardAnchors = remember { TableRewardAnchors() }
    var rewardBurst by remember { mutableStateOf<RewardBurst?>(null) }
    var burstSeq by remember { mutableStateOf(0) }
    var wasXpFrozen by remember { mutableStateOf(xpFrozen) }
    // Baselines snapped the instant the gate freezes (the held displayed
    // values are still the pre-hand numbers at that point), so the release
    // branch can diff against them without racing the displayed-value effects.
    var frozenXpBaseline by remember { mutableStateOf(state.xp) }
    var frozenStackBaseline by remember { mutableStateOf(humanStack) }
    LaunchedEffect(xpFrozen) {
        if (xpFrozen && !wasXpFrozen) {
            frozenXpBaseline = displayedXp
            frozenStackBaseline = displayedHumanStack
        } else if (!xpFrozen && wasXpFrozen) {
            val xpGained = state.xp - frozenXpBaseline
            val coinGained = humanStack - frozenStackBaseline
            if (xpGained > 0 || coinGained > 0) {
                burstSeq += 1
                rewardBurst = RewardBurst(
                    id = burstSeq,
                    flyXp = xpGained > 0,
                    flyCoin = coinGained > 0,
                )
            }
        }
        wasXpFrozen = xpFrozen
    }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(active?.isHumanTurn) {
        if (active?.isHumanTurn != true) {
            actionSheetOpen = false
        } else {
            // Fire the configured "your turn" cue. Both Vibrate and the
            // legacy Sound value perform haptics — Sound is hidden from
            // the picker until the KMP audio path lands (docs/backlog.md),
            // so until then it behaves like Vibrate to match what the
            // settings UI advertises to legacy users.
            when (state.turnFeedback) {
                com.dangerfield.cards.libraries.cards.TurnFeedback.Vibrate,
                com.dangerfield.cards.libraries.cards.TurnFeedback.Sound ->
                    haptics.performHapticFeedback(
                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                    )
                com.dangerfield.cards.libraries.cards.TurnFeedback.Mute -> Unit
            }
        }
    }

    // Confirm-leave gate. Skip the confirmation only when the table is
    // loading or no hand is in progress — there's nothing to lose. When a
    // hand is live, always show the confirmation; leaving costs the hand.
    val handInProgress = active != null && active.handResult == null
    val leaveTable: () -> Unit = {
        onAction(PlayPokerAction.LeaveTable)
        onBack()
    }
    val requestLeave: () -> Unit = {
        if (!confirmLeave || !handInProgress) {
            leaveTable()
        } else {
            leaveConfirmOpen = true
        }
    }
    BackHandler(enabled = true) { requestLeave() }

    // Background paint is driven by the equipped felt — Default keeps the
    // app's stock background; every other style picks a per-felt color.
    // Repaints the moment the equipment flow re-emits, so the user can
    // toggle felts in My Items and see the table change underneath them
    // without leaving the table.
    val tableSurface = feltSurfaceColor(state.equippedFelt)
    // Per-felt accent surface — icon-button backgrounds and any "raised
    // felt" tone elsewhere on the play screen reads this so it stays
    // legible across felt choices instead of clashing with whichever
    // surface color the felt picked.
    val feltAccent = feltAccentSurface(state.equippedFelt)
    // Ambient card-back style — every PlayingCardBack in the composition
    // reads from this without prop-drilling. Same live-toggle story as
    // the felt above; equip a card back from My Items and the opponents'
    // hole-card backs swap underneath them mid-hand.
    CompositionLocalProvider(
        LocalCardBackStyle provides state.equippedCardBack,
        LocalFeltAccentSurface provides feltAccent,
        LocalTableRewardAnchors provides rewardAnchors,
    ) {
    Screen(modifier = modifier, containerColor = tableSurface) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .screenContentPadding(
                    paddingValues = padding,
                    includeHorizontalInsets = false,
                ),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                ConnectionBanner(connection = state.connection)
                TopBar(
                    xp = displayedXp,
                    xpBoostExpiresAtEpochMs = state.xpBoostExpiresAtEpochMs,
                    onBack = requestLeave,
                    onCheatSheet = { onAction(PlayPokerAction.ToggleCheatSheet) },
                    onTapXp = onTapXp,
                    showXpPill = showXpPill,
                    centerSlot = topBarCenterSlot,
                    availableEmojis = state.availableEmojis,
                    emojiCooldownEndsAtEpochMs = state.emojiCooldownEndsAtMs,
                    onBlastEmoji = { emoji ->
                        onAction(PlayPokerAction.BlastEmoji(emoji))
                    },
                )

                if (active?.practiceTierBotsPresent == true) {
                    PracticeTierLabel()
                }

                if (active == null) {
                    LoadingTable()
                } else {
                    ActiveTable(
                        table = active,
                        humanWinOdds = state.humanWinOdds,
                        humanTitle = state.equippedTitle,
                        humanStackOverride = displayedHumanStack,
                        silentSwipeFold = state.swipeFoldGestureAck,
                        winOddsFlipHintSeen = state.winOddsFlipHintSeen,
                        onWinOddsFlipped = {
                            onAction(PlayPokerAction.MarkWinOddsFlipHintSeen)
                        },
                        onIntent = { onAction(PlayPokerAction.Submit(it)) },
                        onExpandRaise = { actionSheetOpen = true },
                        onBlindClick = { blindExplainerOpen = true },
                        onPotClick = { potExplainerOpen = true },
                        onBetPillClick = { name, amount -> betPillDialog = name to amount },
                        onLastActionClick = { name, action -> lastActionDialog = name to action },
                        onStackClick = { stackExplainerOpen = true },
                        onHandLabelClick = { label -> handLabelDialog = label },
                        onSwipeFold = {
                            val humanIndex = active.seats.firstOrNull { it.isHuman }?.index
                                ?: return@ActiveTable
                            if (state.swipeFoldGestureAck) {
                                onAction(PlayPokerAction.Submit(PlayerIntent.Fold(humanIndex)))
                            } else {
                                swipeFoldConfirmOpen = true
                            }
                        },
                        onOpponentTap = { seat ->
                            seatMuteKey(seat)?.let { profileSheetSeat = seat }
                        },
                        onSelfTap = { selfCardOpen = true },
                    )
                }
            }

        }

        // Render the action sheet as a real bottom sheet so the user gets the
        // expected affordances — drag-to-dismiss, scrim, tap-outside-to-close,
        // and a built-in title + close X — without rolling those ourselves.
        val legal = active?.humanLegalActions
        if (actionSheetOpen && active?.isHumanTurn == true && legal != null) {
            BottomSheet(
                onDismissRequest = { actionSheetOpen = false },
                backgroundColor = AppTheme.colors.surface,
                showCloseButton = true,
            ) {
                PlayerActionSheet(
                    legal = legal,
                    humanSeatIndex = active.seats.first { it.isHuman }.index,
                    onIntent = { intent ->
                        actionSheetOpen = false
                        onAction(PlayPokerAction.Submit(intent))
                    },
                )
            }
        }

        if (state.cheatSheetOpen) {
            val humanSeat = active?.seats?.firstOrNull { it.isHuman }
            HandRankingsCheatSheet(
                onDismiss = { onAction(PlayPokerAction.ToggleCheatSheet) },
                handNumber = active?.handNumber,
                street = active?.street,
                pot = active?.pot,
                holeCards = humanSeat?.holeCards.orEmpty(),
                boardCards = active?.communityCards.orEmpty(),
            )
        }

        if (blindExplainerOpen) {
            BlindRolesExplainer(onDismiss = { blindExplainerOpen = false })
        }

        if (potExplainerOpen) {
            PotExplainer(onDismiss = { potExplainerOpen = false })
        }

        if (stackExplainerOpen) {
            StackExplainer(stack = humanStack, onDismiss = { stackExplainerOpen = false })
        }

        lastActionDialog?.let { (name, action) ->
            LastActionExplainer(
                seatName = name,
                action = action,
                onDismiss = { lastActionDialog = null },
            )
        }

        betPillDialog?.let { (name, amount) ->
            BetPillExplainer(
                seatName = name,
                amount = amount,
                onDismiss = { betPillDialog = null },
            )
        }

        handLabelDialog?.let { label ->
            HandLabelExplainer(
                label = label,
                onDismiss = { handLabelDialog = null },
            )
        }

        if (leaveConfirmOpen) {
            LeaveBotsConfirmDialog(
                onStay = { leaveConfirmOpen = false },
                onLeave = {
                    leaveConfirmOpen = false
                    leaveTable()
                },
            )
        }

        if (swipeFoldConfirmOpen) {
            SwipeFoldConfirmDialog(
                onCancel = { swipeFoldConfirmOpen = false },
                onConfirmFold = { dontShowAgain ->
                    swipeFoldConfirmOpen = false
                    if (dontShowAgain) {
                        onAction(PlayPokerAction.AcknowledgeSwipeFoldGesture)
                    }
                    val humanIndex = active?.seats?.firstOrNull { it.isHuman }?.index
                    if (humanIndex != null) {
                        onAction(PlayPokerAction.Submit(PlayerIntent.Fold(humanIndex)))
                    }
                },
            )
        }

        // Full-screen emoji blast overlay. Renders at the top-level Box so
        // it floats over the table without being clipped by the inner
        // Column's padding. Emitter avatar is rendered beneath the emoji
        // so the blast reads as "Bob just threw this", not just an
        // anonymous emoji on the screen — sets up the MP visual now
        // even though in V1 only the human emits.
        state.emojiBlast?.let { blast ->
            // Attribute to the emitting seat for an opponent's emote;
            // fall back to the human seat for our own outbound blast.
            val emitterSeat = state.emojiBlastEmitterSeatIndex
                ?.let { idx -> active?.seats?.firstOrNull { it.index == idx } }
                ?: active?.seats?.firstOrNull { it.isHuman }
            EmojiBlastOverlay(
                blast = blast,
                onAnimationComplete = { ts ->
                    onAction(PlayPokerAction.EmojiBlastConsumed(ts))
                },
                emitterName = emitterSeat?.displayName,
                emitterEmoji = emitterSeat?.emoji,
                emitterColorHex = emitterSeat?.avatarBackgroundColorHex,
            )
        }

        profileSheetSeat?.let { seat ->
            PlayerProfileSheet(
                seat = seat,
                isMuted = seatMuteKey(seat) in state.mutedEmojiPlayerKeys,
                onToggleMute = {
                    seatMuteKey(seat)?.let { key ->
                        onAction(PlayPokerAction.ToggleMutePlayer(key))
                    }
                },
                onDismiss = { profileSheetSeat = null },
                // Resolve the opponent's equipped badge ids (off their Seat) to
                // display metadata from our catalog — no earned-at for opponents,
                // so the sheet shows what it is, not when they earned it.
                badges = resolvePlayerBadges(seat.equippedBadgeProductIds, state.catalog),
                onBadgeClick = { selectedBadge = it },
                botDifficultyLabel = active?.botDifficultyLabel,
            )
        }

        if (selfCardOpen) {
            active?.seats?.firstOrNull { it.isHuman }?.let { human ->
                PlayerProfileSheet(
                    seat = human,
                    isMePlayer = true,
                    isMuted = false,
                    onToggleMute = {},
                    onDismiss = { selfCardOpen = false },
                    badges = state.equippedBadges,
                    onBadgeClick = { selectedBadge = it },
                )
            }
        }

        selectedBadge?.let { badge ->
            BadgeDetailSheet(badge = badge, onDismiss = { selectedBadge = null })
        }

        // Bot-mode achievement-unlock celebration is sequenced *after* the
        // showdown / bust dialog dismisses so the unlock isn't crammed into
        // the dialog summary (the legacy inline shape still renders for MP
        // unlocks — fast feedback in the middle of a live game). The
        // `celebrationActive` flag (declared at the top of the screen so
        // the displayed-XP gate can read it) gates which surface is on
        // screen during the brief window after dismissal: the dialog hides,
        // the sheet shows, then the next-hand request fires when the sheet
        // is dismissed.
        val isBots = state.xpMode == com.dangerfield.cards.libraries.cards.XpMode.BOTS

        // Resolve a held advance (fast tap during async achievement computation)
        // the instant recordHand settles: reveal if anything was earned,
        // otherwise advance. Without this, a tap before the unlock is computed
        // would skip the celebration entirely.
        LaunchedEffect(advanceRequested, state.awaitingHandEndAchievements) {
            if (advanceRequested && !state.awaitingHandEndAchievements) {
                advanceRequested = false
                if (isBots && state.recentlyEarned.isNotEmpty()) {
                    celebrationActive = true
                } else {
                    onAction(PlayPokerAction.RequestNextHand)
                }
            }
        }

        val handResult = active?.handResult
        if (handResult != null && active.seats.isNotEmpty() && !celebrationActive) {
            val humanSeat = active.seats.firstOrNull { it.isHuman }
            val humanBust = humanSeat != null && humanSeat.stack <= 0
            val onDismiss: () -> Unit = {
                when {
                    // Achievements already in hand → reveal them.
                    isBots && state.recentlyEarned.isNotEmpty() -> celebrationActive = true
                    // Still computing → hold; the effect above advances or
                    // reveals once it lands, so a fast tap can't skip a reveal.
                    isBots && state.awaitingHandEndAchievements -> advanceRequested = true
                    else -> onAction(PlayPokerAction.RequestNextHand)
                }
            }
            if (humanBust) {
                // Bust takes over the moment — focused "you went bust, here's
                // a fresh stack" modal instead of the full showdown. Per the
                // V1 decision (docs/decisions.md 2026-05-14) bot stacks
                // auto-rebuy between hands; this dialog just makes that
                // recovery visible so new players aren't confused. Always
                // shows; busting is a real moment.
                BustDialog(
                    xpEarned = state.lastHandXpAwarded,
                    earnedAchievements = state.recentlyEarned,
                    xpMode = state.xpMode,
                    onDealMeIn = onDismiss,
                )
            } else {
                ShowdownDialog(
                    result = handResult,
                    seats = active.seats,
                    xpEarned = state.lastHandXpAwarded,
                    earnedAchievements = state.recentlyEarned,
                    xpMode = state.xpMode,
                    onNextHand = onDismiss,
                )
            }
        }

        if (celebrationActive && state.recentlyEarned.isNotEmpty()) {
            AchievementCelebrationSheet(
                earned = state.recentlyEarned,
                onContinue = {
                    celebrationActive = false
                    onAction(PlayPokerAction.RequestNextHand)
                },
            )
        }

        // Sits last in the Box so the flying tokens draw over the table after
        // the result overlays have cleared.
        HandRewardParticleOverlay(
            burst = rewardBurst,
            anchors = rewardAnchors,
            onComplete = { rewardBurst = null },
        )
    }
    } // close CompositionLocalProvider
}

/**
 * Slim banner that surfaces non-[ConnectionState.Connected] states. Sits at
 * the top of the screen column so it pushes the rest of the play surface
 * down (rather than overlaying it), matching the rest of the app's banner
 * convention. Copy from voice-and-copy.md §4.3. Local solo sessions never
 * leave [Connected], so this is a no-op for the bot table.
 */
@Composable
private fun ConnectionBanner(connection: ConnectionState) {
    if (connection == ConnectionState.Connected) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(Radii.Callout.shape)
            .background(AppTheme.colors.danger.color.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.room_connection_lost_banner),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.content,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Table-side notice that this MP hand earns only practice-tier credit
 * because the table is bot-stacked (product-spec.md §5.4). Surfaced under
 * the top bar so the player understands why their XP / achievements read
 * "practice" rather than full multiplayer — never a silent downgrade.
 */
@Composable
private fun PracticeTierLabel() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.room_practice_tier_bots_present),
            typography = AppTheme.typography.Caption.C400,
            color = AppTheme.colors.contentSecondary,
            modifier = Modifier
                .clip(Radii.Callout.shape)
                .background(AppTheme.colors.surface.color)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun TopBar(
    xp: Long,
    xpBoostExpiresAtEpochMs: Long? = null,
    onBack: () -> Unit,
    onCheatSheet: () -> Unit,
    onTapXp: () -> Unit = {},
    showXpPill: Boolean = true,
    centerSlot: (@Composable () -> Unit)? = null,
    availableEmojis: List<String> = emptyList(),
    emojiCooldownEndsAtEpochMs: Long = 0L,
    onBlastEmoji: ((String) -> Unit)? = null,
) {
    // Minimal top bar — navigation, level + ring, info. The level pill
    // ticks up live as the player earns XP, and the gradient ring fills
    // toward the next level so the player feels progress even when they
    // lose a hand. Emoji blast lives here alongside the cheat sheet
    // (right-side action cluster) — the trigger always renders so the
    // affordance is visible; the tray itself swaps to an empty-state
    // popup (greyed preview + caption) when the user owns no pack — no
    // in-game shop navigation, which would forfeit the seat.
    //
    // The pill is overlay-positioned at true screen-center via Box
    // alignment rather than placed inline in a SpaceBetween Row.
    // SpaceBetween distributes children based on side widths, so the
    // pill drifts off-center whenever the right cluster grows (e.g. the
    // emoji button appears). Box(CenterStart/Center/CenterEnd) keeps
    // the pill pinned to the screen midpoint regardless.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        IconButton(
            icon = Icons.ArrowBack(stringResource(Res.string.room_top_bar_back_a11y)),
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        if (centerSlot != null) {
            // Caller-supplied content (e.g. the tutorial's step counter) owns
            // the centered slot — same spot the Level pill would sit, so it
            // reads as part of the top bar rather than a floating overlay.
            Box(modifier = Modifier.align(Alignment.Center)) { centerSlot() }
        } else if (showXpPill) {
            // Publish the pill's on-screen bounds so the hand-end XP particle
            // knows where to fly. No-op when there's no anchor holder in scope.
            val anchors = LocalTableRewardAnchors.current
            LevelPill(
                xp = xp,
                onClick = onTapXp,
                boostExpiresAtEpochMs = xpBoostExpiresAtEpochMs,
                modifier = Modifier
                    .align(Alignment.Center)
                    .then(
                        if (anchors != null) {
                            Modifier.onGloballyPositioned { anchors.levelPillBounds = it.boundsInRoot() }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBlastEmoji != null) {
                TopBarEmojiButton(
                    emojis = availableEmojis,
                    cooldownEndsAtEpochMs = emojiCooldownEndsAtEpochMs,
                    onBlast = onBlastEmoji,
                )
                HorizontalSpacerD200()
            }
            IconButton(
                backgroundColor = AppTheme.colors.surface,
                icon = Icons.Question(
                    stringResource(Res.string.room_top_bar_hand_info_a11y),
                ),
                onClick = onCheatSheet,
            )
        }
    }
}


@Composable
private fun LoadingTable() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(Res.string.room_loading_dealing_in),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.content,
        )
    }
}

@Composable
private fun ActiveTable(
    table: TableUiState.Active,
    humanWinOdds: EquityBreakdown?,
    humanTitle: String?,
    humanStackOverride: Long? = null,
    silentSwipeFold: Boolean = false,
    winOddsFlipHintSeen: Boolean = false,
    onWinOddsFlipped: () -> Unit = {},
    onIntent: (PlayerIntent) -> Unit,
    onExpandRaise: () -> Unit,
    onBlindClick: () -> Unit,
    onPotClick: () -> Unit,
    onBetPillClick: (seatName: String, amount: Long) -> Unit = { _, _ -> },
    onLastActionClick: (seatName: String, action: PlayerAction) -> Unit = { _, _ -> },
    onStackClick: () -> Unit = {},
    onHandLabelClick: (label: String) -> Unit = {},
    onSwipeFold: () -> Unit = {},
    onOpponentTap: (SeatView) -> Unit = {},
    onSelfTap: () -> Unit = {},
) {
    // Pinned-bottom layout: opponents + board scroll if needed, but the
    // player's hand and the action bar always sit at the bottom in reach.
    // No "Your turn" banner — the pulsing gold band on the active player
    // (human or bot) carries that signal visually.
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // Clearance above the opponents row so the chevron + last-
            // action pill (both rendered as TopCenter overlays on each
            // avatar with negative Y offsets, ~24dp of upward overflow)
            // have room to breathe instead of being clipped by the
            // TopBar. The LazyRow's own top contentPadding
            // (ScrollingRowOverhangPadding, 28dp) covers the overlay
            // itself — this spacer is just visual breathing room from
            // the TopBar, so it stays modest.
            VerticalSpacerD700()
            OpponentsRow(
                table = table,
                onBlindClick = onBlindClick,
                onBetPillClick = onBetPillClick,
                onLastActionClick = onLastActionClick,
                onAvatarTap = onOpponentTap,
            )

            VerticalSpacerD800()
            BoardArea(table = table, onPotClick = onPotClick)
        }

        // Player row + action bar share a Column so that when the action bar
        // collapses (no human turn, hand finished), this whole block shrinks
        // and the player row slides DOWN to occupy the freed space —
        // instead of sitting in place above an empty reserved slot.
        Column(modifier = Modifier.fillMaxWidth()) {
            PlayerArea(
                table = table,
                humanTitle = humanTitle,
                humanStackOverride = humanStackOverride,
                humanWinOdds = humanWinOdds,
                silentSwipeFold = silentSwipeFold,
                winOddsFlipHintSeen = winOddsFlipHintSeen,
                onWinOddsFlipped = onWinOddsFlipped,
                onBlindClick = onBlindClick,
                onBetPillClick = onBetPillClick,
                onLastActionClick = onLastActionClick,
                onStackClick = onStackClick,
                onHandLabelClick = onHandLabelClick,
                onSwipeFold = onSwipeFold,
                onSelfTap = onSelfTap,
            )
            QuickActionBar(table = table, onIntent = onIntent, onExpandRaise = onExpandRaise)
        }
    }
}

// --------------------------------------------------------------------------
// Preview fixtures
//
// Sample states for the bot table. Keep these private so they don't escape
// into production code paths — they exist solely for @Preview rendering and
// are too synthetic to use as test data.
// --------------------------------------------------------------------------

private fun card(rank: Rank, suit: Suit): Card = Card(rank, suit)

private fun previewBotEmoji(name: String): String = when (name) {
    "Jane" -> "🧐"
    "David" -> "😎"
    "Gina" -> "🦊"
    "Steve" -> "🐢"
    "Mike" -> "🤡"
    else -> "🤖"
}

private fun previewHumanSeat(
    stack: Long = 980,
    contributed: Long = 0,
    isActing: Boolean = true,
    holeCards: List<Card> = listOf(card(Rank.Ace, Suit.Spades), card(Rank.King, Suit.Spades)),
    isDealer: Boolean = true,
    isSmallBlind: Boolean = false,
    isBigBlind: Boolean = false,
    lastAction: PlayerAction? = null,
): SeatView = SeatView(
    index = 0,
    displayName = "You",
    stack = stack,
    contributedThisStreet = contributed,
    isActing = isActing,
    isHuman = true,
    isBot = false,
    avatarKey = null,
    emoji = null,
    holeCards = holeCards,
    showHoleCardBacks = false,
    participation = HandParticipation.InHand,
    seatEmpty = false,
    isBusted = false,
    lastAction = lastAction,
    isDealer = isDealer,
    isSmallBlind = isSmallBlind,
    isBigBlind = isBigBlind,
)

private fun previewBotSeat(
    index: Int,
    name: String,
    stack: Long = 1000,
    contributed: Long = 0,
    isActing: Boolean = false,
    participation: HandParticipation = HandParticipation.InHand,
    holeCards: List<Card> = emptyList(),
    lastAction: PlayerAction? = null,
    isDealer: Boolean = false,
    isSmallBlind: Boolean = false,
    isBigBlind: Boolean = false,
): SeatView = SeatView(
    index = index,
    displayName = name,
    stack = stack,
    contributedThisStreet = contributed,
    isActing = isActing,
    isHuman = false,
    isBot = true,
    avatarKey = "avatar_$name",
    emoji = previewBotEmoji(name),
    holeCards = holeCards,
    showHoleCardBacks = participation == HandParticipation.InHand && holeCards.isEmpty(),
    participation = participation,
    seatEmpty = false,
    isBusted = stack <= 0L,
    lastAction = lastAction,
    isDealer = isDealer,
    isSmallBlind = isSmallBlind,
    isBigBlind = isBigBlind,
)

private fun previewActive(
    street: BettingRound = BettingRound.Preflop,
    communityCards: List<Card> = emptyList(),
    pot: Long = 30,
    seats: List<SeatView> = previewDefaultSeats(),
    actingSeatIndex: Int? = 0,
    isHumanTurn: Boolean = actingSeatIndex == 0,
    humanLegalActions: LegalActions? = previewLegalActions(canRaise = true, isOpenBet = false, callAmount = 20),
    humanHandLabel: String? = null,
    handResult: HandResultView? = null,
    handNumber: Int = 1,
    buttonSeatIndex: Int = 0,
    smallBlindSeatIndex: Int? = 1,
    bigBlindSeatIndex: Int? = 2,
    practiceTierBotsPresent: Boolean = false,
): TableUiState.Active = TableUiState.Active(
    street = street,
    communityCards = communityCards,
    pot = pot,
    potCommittedThisStreet = pot,
    seats = seats,
    actingSeatIndex = actingSeatIndex,
    isHumanTurn = isHumanTurn,
    humanLegalActions = humanLegalActions,
    humanHandLabel = humanHandLabel,
    handResult = handResult,
    smallBlind = 10,
    bigBlind = 20,
    handNumber = handNumber,
    buttonSeatIndex = buttonSeatIndex,
    smallBlindSeatIndex = smallBlindSeatIndex,
    bigBlindSeatIndex = bigBlindSeatIndex,
    practiceTierBotsPresent = practiceTierBotsPresent,
)

private fun previewDefaultSeats(): List<SeatView> = listOf(
    previewHumanSeat(isActing = true, isDealer = true),
    previewBotSeat(index = 1, name = "David", isSmallBlind = true, contributed = 10),
    previewBotSeat(index = 2, name = "Jane", isBigBlind = true, contributed = 20),
    previewBotSeat(index = 3, name = "Mike"),
)

private fun previewLegalActions(
    canCheck: Boolean = false,
    canCall: Boolean = true,
    callAmount: Long = 20,
    canRaise: Boolean = true,
    isOpenBet: Boolean = false,
    minRaiseTotal: Long = 40,
    maxRaiseTotal: Long = 980,
    potIfYouCall: Long = 50,
): LegalActions = LegalActions(
    canCheck = canCheck,
    canCall = canCall,
    callAmount = callAmount,
    canRaise = canRaise,
    isOpenBet = isOpenBet,
    minRaiseTotal = minRaiseTotal,
    maxRaiseTotal = maxRaiseTotal,
    canAllIn = true,
    allInAmount = 980,
    potIfYouCall = potIfYouCall,
)

@Preview
@Composable
private fun PlayPokerScreenPreview_YourTurnPreflop() {
    PreviewContent {
        PlayPokerScreen(
            state = PlayPokerState(table = previewActive()),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview(widthDp = 800, heightDp = 380)
@Composable
private fun PlayPokerScreenPreview_Landscape() {
    // The table is the screen most likely to need bespoke landscape work —
    // seats, board, and the action bar all compete for a short, wide canvas.
    // This pins that state for review before any landscape layout lands.
    PreviewContent {
        PlayPokerScreen(
            state = PlayPokerState(table = previewActive()),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayPokerScreenPreview_PracticeTier() {
    // Bot-stacked MP table (2 humans + 4 bots) — the "Practice tier · bots
    // present" label renders under the top bar so the credit downgrade is
    // never silent (product-spec.md §5.4).
    PreviewContent {
        PlayPokerScreen(
            state = PlayPokerState(table = previewActive(practiceTierBotsPresent = true)),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayPokerScreenPreview_Emoji() {
    PreviewContent {
        PlayPokerScreen(
            state = PlayPokerState(
                table = previewActive(),
                availableEmojis = listOf("😂", "👍", "😎")
            ),
            onAction = {},
            onBack = {},
        )
    }
}


@Preview
@Composable
private fun PlayPokerScreenPreview_YourTurnPostflopOpenBet() {
    PreviewContent {
        PlayPokerScreen(
            state = PlayPokerState(
                table = previewActive(
                    street = BettingRound.Flop,
                    communityCards = listOf(
                        card(Rank.Ten, Suit.Hearts),
                        card(Rank.Seven, Suit.Clubs),
                        card(Rank.Two, Suit.Diamonds),
                    ),
                    pot = 60,
                    seats = previewDefaultSeats().map {
                        if (it.index == 1) it.copy(lastAction = PlayerAction.Check)
                        else if (it.index == 2) it.copy(lastAction = PlayerAction.Check)
                        else it
                    },
                    humanLegalActions = previewLegalActions(
                        canCheck = true,
                        canCall = false,
                        callAmount = 0,
                        isOpenBet = true,
                        minRaiseTotal = 20,
                    ),
                    humanHandLabel = "High card · Ace",
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayPokerScreenPreview_RaiseUnavailable() {
    PreviewContent {
        PlayPokerScreen(
            state = PlayPokerState(
                table = previewActive(
                    seats = previewDefaultSeats().map {
                        if (it.isHuman) it.copy(stack = 15) else it
                    },
                    humanLegalActions = previewLegalActions(
                        canRaise = false,
                        callAmount = 20,
                        minRaiseTotal = 40,
                        maxRaiseTotal = 15,
                    ),
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayPokerScreenPreview_BotThinking() {
    PreviewContent {
        PlayPokerScreen(
            state = PlayPokerState(
                table = previewActive(
                    actingSeatIndex = 3,
                    isHumanTurn = false,
                    humanLegalActions = null,
                    seats = previewDefaultSeats().map {
                        when (it.index) {
                            0 -> it.copy(isActing = false)
                            3 -> it.copy(isActing = true)
                            else -> it
                        }
                    },
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayPokerScreenPreview_Showdown() {
    val board = listOf(
        card(Rank.Ten, Suit.Hearts),
        card(Rank.Jack, Suit.Hearts),
        card(Rank.Queen, Suit.Hearts),
        card(Rank.Three, Suit.Clubs),
        card(Rank.Seven, Suit.Spades),
    )
    val seats = listOf(
        previewHumanSeat(
            stack = 1080,
            contributed = 0,
            isActing = false,
            holeCards = listOf(card(Rank.Ace, Suit.Hearts), card(Rank.Ace, Suit.Spades)),
        ),
        previewBotSeat(
            index = 1,
            name = "David",
            stack = 920,
            holeCards = listOf(card(Rank.King, Suit.Spades), card(Rank.King, Suit.Diamonds)),
        ),
        previewBotSeat(
            index = 2,
            name = "Jane",
            stack = 880,
            participation = HandParticipation.Folded,
        ),
        previewBotSeat(
            index = 3,
            name = "Mike",
            stack = 920,
            holeCards = listOf(card(Rank.Nine, Suit.Hearts), card(Rank.Eight, Suit.Hearts)),
        ),
    )
    val result = HandResultView(
        winners = listOf(
            HandWinner(
                seatIndex = 0,
                amount = 180,
                handRank = HandEvaluator.evaluate(seats.first().holeCards + board),
                byFold = false,
            ),
        ),
        board = board,
    )
    PreviewContent {
        PlayPokerScreen(
            state = PlayPokerState(
                table = previewActive(
                    street = BettingRound.Showdown,
                    communityCards = board,
                    pot = 180,
                    seats = seats,
                    actingSeatIndex = null,
                    isHumanTurn = false,
                    humanLegalActions = null,
                    handResult = result,
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayPokerScreenPreview_CheatSheet() {
    PreviewContent {
        PlayPokerScreen(
            state = PlayPokerState(table = previewActive(), cheatSheetOpen = true),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayPokerScreenPreview_Loading() {
    PreviewContent {
        PlayPokerScreen(
            state = PlayPokerState(table = TableUiState.Loading),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayPokerScreenPreview_BustDialog() {
    // Hand-over modal that takes over when the human busts. The handResult
    // is present, human stack is 0 → bust dialog rather than showdown.
    val board = listOf(
        card(Rank.Ten, Suit.Hearts),
        card(Rank.Jack, Suit.Hearts),
        card(Rank.Queen, Suit.Hearts),
        card(Rank.Three, Suit.Clubs),
        card(Rank.Seven, Suit.Spades),
    )
    val seats = listOf(
        previewHumanSeat(
            stack = 0L,
            isActing = false,
            holeCards = listOf(card(Rank.Two, Suit.Clubs), card(Rank.Three, Suit.Diamonds)),
        ),
        previewBotSeat(
            index = 1,
            name = "David",
            stack = 2_000,
            holeCards = listOf(card(Rank.Ace, Suit.Hearts), card(Rank.King, Suit.Hearts)),
        ),
    )
    val result = HandResultView(
        winners = listOf(
            HandWinner(
                seatIndex = 1,
                amount = 1_000,
                handRank = HandEvaluator.evaluate(seats[1].holeCards + board),
                byFold = false,
            ),
        ),
        board = board,
    )
    PreviewContent {
        PlayPokerScreen(
            state = PlayPokerState(
                table = previewActive(
                    street = BettingRound.Showdown,
                    communityCards = board,
                    pot = 1_000,
                    seats = seats,
                    actingSeatIndex = null,
                    isHumanTurn = false,
                    humanLegalActions = null,
                    handResult = result,
                ),
                lastHandXpAwarded = 12,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayPokerScreenPreview_HandEndedWithXpAndAchievement() {
    // MP variant — the inline "Achievement unlocked" row stays on the
    // showdown dialog so the game flow isn't interrupted by a follow-up
    // sheet. Bot-mode unlocks render in [AchievementCelebrationSheet]
    // sequenced after the dialog dismisses; that surface has its own
    // previews in AchievementCelebrationSheet.kt.
    val board = listOf(
        card(Rank.Ten, Suit.Hearts),
        card(Rank.Jack, Suit.Hearts),
        card(Rank.Queen, Suit.Hearts),
        card(Rank.Three, Suit.Clubs),
        card(Rank.Seven, Suit.Spades),
    )
    val seats = listOf(
        previewHumanSeat(
            stack = 1_180,
            isActing = false,
            holeCards = listOf(card(Rank.Ace, Suit.Hearts), card(Rank.Ace, Suit.Spades)),
        ),
        previewBotSeat(
            index = 1,
            name = "David",
            stack = 820,
            holeCards = listOf(card(Rank.King, Suit.Spades), card(Rank.King, Suit.Diamonds)),
        ),
    )
    val result = HandResultView(
        winners = listOf(
            HandWinner(
                seatIndex = 0,
                amount = 360,
                handRank = HandEvaluator.evaluate(seats[0].holeCards + board),
                byFold = false,
            ),
        ),
        board = board,
    )
    val earned = listOf(
        com.dangerfield.cards.libraries.cards.EarnedAchievement(
            achievement = com.dangerfield.cards.libraries.cards.Achievement(
                id = com.dangerfield.cards.libraries.cards.AchievementId.FIRST_HAND,
                name = "First Blood",
                description = "Win your first hand against the bots.",
                icon = "trophy",
                rarity = com.dangerfield.cards.libraries.cards.AchievementRarity.COMMON,
                criterion = com.dangerfield.cards.libraries.cards.Criterion.HandsPlayed(target = 1),
                xpReward = 25,
            ),
            earnedAtEpochMs = 0,
        ),
    )
    PreviewContent {
        PlayPokerScreen(
            state = PlayPokerState(
                table = previewActive(
                    street = BettingRound.Showdown,
                    communityCards = board,
                    pot = 360,
                    seats = seats,
                    actingSeatIndex = null,
                    isHumanTurn = false,
                    humanLegalActions = null,
                    handResult = result,
                ),
                lastHandXpAwarded = 47,
                recentlyEarned = earned,
                xpMode = com.dangerfield.cards.libraries.cards.XpMode.MULTIPLAYER,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayPokerScreenPreview_Reconnecting() {
    // Connection banner showing while the socket retries — bot table never
    // hits this in practice (local sessions stay [Connected]), but MP does.
    PreviewContent {
        PlayPokerScreen(
            state = PlayPokerState(
                table = previewActive(),
                connection = ConnectionState.Reconnecting,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayPokerScreenPreview_WonByFold() {
    // Fold-around win: only one winner, byFold=true, no community cards
    // revealed (preflop fold-around), no hole cards revealed for anyone.
    val seats = listOf(
        previewHumanSeat(
            stack = 1_015,
            isActing = false,
            holeCards = listOf(card(Rank.Ace, Suit.Spades), card(Rank.King, Suit.Spades)),
        ),
        previewBotSeat(
            index = 1,
            name = "David",
            stack = 985,
            participation = HandParticipation.Folded,
        ),
    )
    val result = HandResultView(
        winners = listOf(
            HandWinner(seatIndex = 0, amount = 15, handRank = null, byFold = true),
        ),
        board = emptyList(),
    )
    PreviewContent {
        PlayPokerScreen(
            state = PlayPokerState(
                table = previewActive(
                    street = BettingRound.Complete,
                    communityCards = emptyList(),
                    pot = 15,
                    seats = seats,
                    actingSeatIndex = null,
                    isHumanTurn = false,
                    humanLegalActions = null,
                    handResult = result,
                ),
                lastHandXpAwarded = 3,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

// ---------------------------------------------------------------------------
// Felt-color previews — one per [EquippedFelt] enum value. These pin the
// visual regression surface so the cosmetic system can't drift without
// somebody noticing in the IDE preview pane. Each preview reuses the same
// preflop "your turn" table so the only variable across the set is the
// felt color itself.
//
// New felt? Add the enum value to [EquippedFelt] AND a matching preview
// below. The convention is intentional — the preview list is the visual
// regression bar.
// ---------------------------------------------------------------------------

@Composable
private fun PlayPokerScreenFeltPreview(felt: EquippedFelt) {
    PreviewContent {
        PlayPokerScreen(
            state = PlayPokerState(
                table = previewActive(),
                equippedFelt = felt,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayPokerScreenPreview_Felt_Default() =
    PlayPokerScreenFeltPreview(EquippedFelt.Default)

@Preview
@Composable
private fun PlayPokerScreenPreview_Felt_RoyalRed() =
    PlayPokerScreenFeltPreview(EquippedFelt.RoyalRed)

@Preview
@Composable
private fun PlayPokerScreenPreview_Felt_MidnightBlue() =
    PlayPokerScreenFeltPreview(EquippedFelt.MidnightBlue)

@Preview
@Composable
private fun PlayPokerScreenPreview_Felt_Charcoal() =
    PlayPokerScreenFeltPreview(EquippedFelt.Charcoal)

@Preview
@Composable
private fun PlayPokerScreenPreview_Felt_Sunset() =
    PlayPokerScreenFeltPreview(EquippedFelt.Sunset)

@Preview
@Composable
private fun PlayPokerScreenPreview_Felt_Neon() =
    PlayPokerScreenFeltPreview(EquippedFelt.Neon)
