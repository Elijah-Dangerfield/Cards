package com.dangerfield.cards.features.room.impl

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.Slider
import com.dangerfield.cards.libraries.ui.components.XpBadge
import com.dangerfield.cards.libraries.ui.components.formatCompactChips
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BasicBottomSheet
import com.dangerfield.cards.libraries.ui.components.poker.BlindMarker
import com.dangerfield.cards.libraries.ui.components.poker.ChipPill
import com.dangerfield.cards.libraries.ui.components.poker.LastActionPill
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardBack
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSlot
import com.dangerfield.cards.libraries.ui.components.poker.PulsingActiveRing
import com.dangerfield.cards.libraries.ui.components.poker.WinnerGlow
import com.dangerfield.cards.libraries.ui.components.text.BasicTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD1100
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay


@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayBotsScreen(
    state: PlayBotsState,
    onAction: (PlayBotsAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onTapXp: () -> Unit = {},
) {
    var raiseSheetOpen by remember { mutableStateOf(false) }
    var blindExplainerOpen by remember { mutableStateOf(false) }
    var potExplainerOpen by remember { mutableStateOf(false) }
    var stackExplainerOpen by remember { mutableStateOf(false) }
    var leaveConfirmOpen by remember { mutableStateOf(false) }
    // Action / bet / hand-label explainers carry their own context so each
    // dialog can render specific copy instead of opening the whole cheat sheet.
    var lastActionDialog by remember { mutableStateOf<Pair<String, PlayerAction>?>(null) }
    var betPillDialog by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var handLabelDialog by remember { mutableStateOf<String?>(null) }
    val active = state.table as? TableUiState.Active
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(active?.isHumanTurn) {
        if (active?.isHumanTurn != true) {
            raiseSheetOpen = false
        } else {
            // Fire the configured "your turn" cue. Vibrate uses platform
            // haptics (Compose handles Android natively; iOS no-ops in
            // older versions, ok). Sound is wired in the data but the
            // KMP audio path isn't built yet — see docs/backlog.md.
            when (state.turnFeedback) {
                com.dangerfield.cards.libraries.cards.TurnFeedback.Vibrate ->
                    haptics.performHapticFeedback(
                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                    )
                com.dangerfield.cards.libraries.cards.TurnFeedback.Sound -> Unit
                com.dangerfield.cards.libraries.cards.TurnFeedback.Mute -> Unit
            }
        }
    }

    // Confirm-leave gate. Skip the confirmation when:
    //  - the user has previously opted out via "don't show this again", or
    //  - the table is loading / no hand is in progress (no progress to lose).
    val handInProgress = active != null && active.handResult == null
    val requestLeave: () -> Unit = {
        if (state.skipLeaveBotsConfirm || !handInProgress) {
            onBack()
        } else {
            leaveConfirmOpen = true
        }
    }
    BackHandler(enabled = true) { requestLeave() }

    Screen(modifier = modifier) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                TopBar(
                    handNumber = active?.handNumber,
                    street = active?.street,
                    xp = state.xp,
                    onBack = requestLeave,
                    onCheatSheet = { onAction(PlayBotsAction.ToggleCheatSheet) },
                    onTapXp = onTapXp,
                )

                if (active == null) {
                    LoadingTable()
                } else {
                    ActiveTable(
                        table = active,
                        onIntent = { onAction(PlayBotsAction.SubmitIntent(it)) },
                        onExpandRaise = { raiseSheetOpen = true },
                        onBlindClick = { blindExplainerOpen = true },
                        onPotClick = { potExplainerOpen = true },
                        onBetPillClick = { name, amount -> betPillDialog = name to amount },
                        onLastActionClick = { name, action -> lastActionDialog = name to action },
                        onStackClick = { stackExplainerOpen = true },
                        onHandLabelClick = { label -> handLabelDialog = label },
                    )
                }
            }

        }

        // Render the action sheet as a real bottom sheet so the user gets the
        // expected affordances — drag-to-dismiss, scrim, tap-outside-to-close,
        // and a built-in title + close X — without rolling those ourselves.
        val legal = active?.humanLegalActions
        if (raiseSheetOpen && active?.isHumanTurn == true && legal != null) {
            BasicBottomSheet(
                onDismissRequest = { raiseSheetOpen = false },
                backgroundColor = AppTheme.colors.surfacePrimary,
                showCloseButton = true,
            ) {
                RaiseSheet(
                    legal = legal,
                    humanSeatIndex = active.seats.first { it.isHuman }.index,
                    onIntent = { intent ->
                        raiseSheetOpen = false
                        onAction(PlayBotsAction.SubmitIntent(intent))
                    },
                )
            }
        }

        if (state.cheatSheetOpen) {
            HandRankingsCheatSheet(
                onDismiss = { onAction(PlayBotsAction.ToggleCheatSheet) },
                handNumber = active?.handNumber,
                street = active?.street,
                pot = active?.pot,
            )
        }

        if (blindExplainerOpen) {
            BlindRolesExplainer(onDismiss = { blindExplainerOpen = false })
        }

        if (potExplainerOpen) {
            PotExplainer(onDismiss = { potExplainerOpen = false })
        }

        if (stackExplainerOpen) {
            val humanStack = active?.seats?.firstOrNull { it.isHuman }?.stack ?: 0L
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
                    onBack()
                },
                onSetSkipLeaveConfirm = { onAction(PlayBotsAction.SetSkipLeaveConfirm(it)) },
            )
        }

        val handResult = active?.handResult
        if (handResult != null && active.seats.isNotEmpty()) {
            val humanSeat = active.seats.firstOrNull { it.isHuman }
            val humanBust = humanSeat != null && humanSeat.stack <= 0
            if (humanBust) {
                // Bust takes over the moment — focused "you went bust, here's
                // a fresh stack" modal instead of the full showdown. Per the
                // V1 decision (docs/decisions.md 2026-05-14) bot stacks
                // auto-rebuy between hands; this dialog just makes that
                // recovery visible so new players aren't confused.
                //
                // If the user has ticked "Don't show this again" previously,
                // skip the modal entirely and advance straight to the next
                // hand the moment we observe a bust.
                if (state.skipBustDialog) {
                    LaunchedEffect(handResult) {
                        onAction(PlayBotsAction.AdvanceNextHand)
                    }
                } else {
                    BustDialog(
                        xpEarned = state.lastHandXpAwarded,
                        earnedAchievements = state.recentlyEarned,
                        onDealMeIn = { onAction(PlayBotsAction.AdvanceNextHand) },
                        onSetSkipBustDialog = { onAction(PlayBotsAction.SetSkipBustDialog(it)) },
                    )
                }
            } else {
                ShowdownDialog(
                    result = handResult,
                    seats = active.seats,
                    xpEarned = state.lastHandXpAwarded,
                    earnedAchievements = state.recentlyEarned,
                    onNextHand = { onAction(PlayBotsAction.AdvanceNextHand) },
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    handNumber: Int?,
    street: BettingRound?,
    xp: Long,
    onBack: () -> Unit,
    onCheatSheet: () -> Unit,
    onTapXp: () -> Unit = {},
) {
    // Minimal top row — navigation, lifetime XP, info. XP appears here so the
    // counter ticks up live during a session and the player feels progress
    // even when they lose a hand.
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = AppTheme.colors.text.color,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        XpBadge(xp = xp, onClick = onTapXp)
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onCheatSheet) {
            Icon(
                imageVector = Icons.Default.HelpOutline,
                contentDescription = "Hand info and rankings",
                tint = AppTheme.colors.text.color,
            )
        }
    }
}

private fun streetLabel(street: BettingRound): String = when (street) {
    BettingRound.Preflop -> "Preflop"
    BettingRound.Flop -> "Flop"
    BettingRound.Turn -> "Turn"
    BettingRound.River -> "River"
    BettingRound.Showdown -> "Showdown"
    BettingRound.Complete -> "Hand complete"
}

@Composable
private fun LoadingTable() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Dealing in…",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun ActiveTable(
    table: TableUiState.Active,
    onIntent: (PlayerIntent) -> Unit,
    onExpandRaise: () -> Unit,
    onBlindClick: () -> Unit,
    onPotClick: () -> Unit,
    onBetPillClick: (seatName: String, amount: Long) -> Unit = { _, _ -> },
    onLastActionClick: (seatName: String, action: PlayerAction) -> Unit = { _, _ -> },
    onStackClick: () -> Unit = {},
    onHandLabelClick: (label: String) -> Unit = {},
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
            // Tall clearance above the opponents row so the chevron + last-
            // action pill (both rendered as TopCenter overlays on each avatar
            // with negative Y offsets, ~24dp of upward overflow) have room to
            // breathe instead of being clipped by the TopBar.
            VerticalSpacerD1100()
            OpponentsRow(
                table = table,
                onBlindClick = onBlindClick,
                onBetPillClick = onBetPillClick,
                onLastActionClick = onLastActionClick,
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
                onBlindClick = onBlindClick,
                onBetPillClick = onBetPillClick,
                onLastActionClick = onLastActionClick,
                onStackClick = onStackClick,
                onHandLabelClick = onHandLabelClick,
            )
            QuickActionBar(table = table, onIntent = onIntent, onExpandRaise = onExpandRaise)
            VerticalSpacerD500()
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
    lastBotThoughts = emptyMap(),
    handResult = handResult,
    smallBlind = 10,
    bigBlind = 20,
    handNumber = handNumber,
    buttonSeatIndex = buttonSeatIndex,
    smallBlindSeatIndex = smallBlindSeatIndex,
    bigBlindSeatIndex = bigBlindSeatIndex,
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
private fun PlayBotsScreenPreview_YourTurnPreflop() {
    PreviewContent {
        PlayBotsScreen(
            state = PlayBotsState(table = previewActive()),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayBotsScreenPreview_YourTurnPostflopOpenBet() {
    PreviewContent {
        PlayBotsScreen(
            state = PlayBotsState(
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
private fun PlayBotsScreenPreview_RaiseUnavailable() {
    PreviewContent {
        PlayBotsScreen(
            state = PlayBotsState(
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
private fun PlayBotsScreenPreview_BotThinking() {
    PreviewContent {
        PlayBotsScreen(
            state = PlayBotsState(
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
private fun PlayBotsScreenPreview_Showdown() {
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
        PlayBotsScreen(
            state = PlayBotsState(
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
private fun PlayBotsScreenPreview_CheatSheet() {
    PreviewContent {
        PlayBotsScreen(
            state = PlayBotsState(table = previewActive(), cheatSheetOpen = true),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayBotsScreenPreview_Loading() {
    PreviewContent {
        PlayBotsScreen(
            state = PlayBotsState(table = TableUiState.Loading),
            onAction = {},
            onBack = {},
        )
    }
}
