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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.formatCompactChips
import com.dangerfield.cards.libraries.ui.components.poker.BlindMarker
import com.dangerfield.cards.libraries.ui.components.poker.ChipPill
import com.dangerfield.cards.libraries.ui.components.poker.LastActionPill
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardBack
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSlot
import com.dangerfield.cards.libraries.ui.components.poker.WinnerGlow
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD100
import com.dangerfield.cards.system.VerticalSpacerD300
import kotlinx.coroutines.delay
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
internal fun PlayerArea(
    table: TableUiState.Active,
    humanTitle: String? = null,
    onBlindClick: () -> Unit = {},
    onBetPillClick: (seatName: String, amount: Long) -> Unit = { _, _ -> },
    onLastActionClick: (seatName: String, action: com.dangerfield.cards.libraries.gameplay.PlayerAction) -> Unit = { _, _ -> },
    onStackClick: () -> Unit = {},
    onHandLabelClick: (label: String) -> Unit = {},
) {
    val human = table.seats.firstOrNull { it.isHuman } ?: return
    val folded = human.participation == HandParticipation.Folded
    val isWinner = table.handResult?.winners?.any { it.seatIndex == human.index } == true
    // Pulse the band around the whole player area when it's the human's turn.
    // This replaces the dropped "Your turn" text banner.
    val pulseAlpha = if (human.isActing) pulseAlpha(low = 0.30f, high = 0.85f) else 0f
    val borderColor = when {
        isWinner -> PokerPalette.ChipGold
        human.isActing -> PokerPalette.SeatActive.copy(alpha = pulseAlpha)
        else -> Color.Transparent
    }
    val borderWidth = if (isWinner || human.isActing) 2.dp else 0.dp
    // Fixed row height — children inside use `fillMaxHeight()`, so this MUST
    // be bounded. `heightIn(min)` would let `fillMaxHeight` expand to the
    // parent's full offered height and the row would eat the whole screen.
    // Matches hole-card height so the info tile and the hole cards visually
    // align — the tile's content is sized tight enough to fit in this height
    // without clipping (see PlayerInfoTile).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .height(PlayingCardSize.Hole.height),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .alpha(if (folded) 0.35f else 1f),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy((-28).dp)) {
                HoleCardSlot(card = human.holeCards.getOrNull(0), dealDelayMs = 0, size = PlayingCardSize.Hole)
                HoleCardSlot(card = human.holeCards.getOrNull(1), dealDelayMs = 150, size = PlayingCardSize.Hole)
            }
        }
        PlayerInfoTile(
            seat = human,
            handLabel = table.humanHandLabel,
            isWinner = isWinner,
            title = humanTitle,
            onBlindClick = onBlindClick,
            onBetPillClick = onBetPillClick,
            onLastActionClick = onLastActionClick,
            onStackClick = onStackClick,
            onHandLabelClick = onHandLabelClick,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

/**
 * Drives the active-turn pulse for the human's player-area border. The
 * opponent ring uses its own self-contained `PulsingActiveRing` in
 * `libraries/ui`; we keep this helper local because the border alpha is
 * woven into a `Color.copy(alpha = ...)` derivation that doesn't fit the
 * library component's API.
 */
@Composable
private fun pulseAlpha(low: Float = 0.32f, high: Float = 0.78f): Float {
    val transition = rememberInfiniteTransition(label = "active-pulse")
    val alpha by transition.animateFloat(
        initialValue = low,
        targetValue = high,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    return alpha
}

@Composable
private fun HoleCardSlot(card: Card?, dealDelayMs: Int, size: PlayingCardSize) {
    if (card == null) {
        PlayingCardSlot(size = size)
        return
    }
    val inPreview = LocalInspectionMode.current
    key(card) {
        var arrived by remember { mutableStateOf(inPreview) }
        var revealed by remember { mutableStateOf(inPreview) }
        var settled by remember { mutableStateOf(inPreview) }
        if (!inPreview) {
            LaunchedEffect(Unit) {
                delay(dealDelayMs.toLong())
                arrived = true
                delay(320)
                revealed = true
                delay(420)
                settled = true
            }
        }
        if (settled) {
            PlayingCard(card = card, size = size)
        } else {
            val flightDp = -260f
            val flightPx = with(LocalDensity.current) { flightDp.dp.toPx() }
            val translationY by animateFloatAsState(
                targetValue = if (arrived) 0f else flightPx,
                animationSpec = tween(360, easing = FastOutSlowInEasing),
                label = "hole-fly",
            )
            val rotation by animateFloatAsState(
                targetValue = if (revealed) 180f else 0f,
                animationSpec = tween(380),
                label = "hole-flip",
            )
            Box(
                modifier = Modifier
                    .size(width = size.width, height = size.height)
                    .graphicsLayer {
                        this.translationY = translationY
                        rotationY = rotation
                        cameraDistance = 12f * density
                    },
            ) {
                if (rotation <= 90f) {
                    PlayingCardBack(size = size)
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().graphicsLayer { rotationY = 180f },
                    ) {
                        PlayingCard(card = card, size = size)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerInfoTile(
    seat: SeatView,
    handLabel: String?,
    isWinner: Boolean,
    title: String?,
    onBlindClick: () -> Unit,
    onBetPillClick: (seatName: String, amount: Long) -> Unit,
    onLastActionClick: (seatName: String, action: com.dangerfield.cards.libraries.gameplay.PlayerAction) -> Unit,
    onStackClick: () -> Unit,
    onHandLabelClick: (label: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasBlindRole = seat.isDealer || seat.isSmallBlind || seat.isBigBlind
    // The outer PlayerArea now carries the active-turn band and the winner
    // highlight, so this tile stays neutral — just the player's avatar and chip
    // info. Keeps the visual hierarchy clean (one gold accent, not two nested ones).
    //
    // Dimensions are tuned tight so the content fits inside the locked
    // hole-card row height (PlayingCardSize.Hole.height = 142.dp) without
    // clipping the bottom chip pill. Adjust together if the row height changes.
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AppTheme.colors.surfacePrimary.color)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (handLabel != null) {
            Text(
                text = handLabel,
                typography = AppTheme.typography.Body.B500.Bold,
                color = AppTheme.colors.text,
                modifier = Modifier.clickable { onHandLabelClick(handLabel) },
            )
            VerticalSpacerD100()
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
            if (isWinner) WinnerGlow(modifier = Modifier.size(48.dp))
            AvatarCircle(name = "You", size = 44.dp, emoji = AnonymousAvatarEmoji)
            BlindMarker(
                isDealer = seat.isDealer,
                isSmallBlind = seat.isSmallBlind,
                isBigBlind = seat.isBigBlind,
                onClick = if (hasBlindRole) onBlindClick else null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // x near zero pushes the chip toward the avatar's right
                    // edge (less invading the emoji); y nudged up gives extra
                    // breathing room between the marker and the stack number
                    // below so the two tap targets don't crowd each other.
                    .offset(x = (-2).dp, y = (-8).dp),
            )
        }
        // Equipped title — "Bluff Master", "The Shark", "High Roller".
        // Renders between avatar + stack, kept to a single line via
        // maxLines + ellipsis so a future longer title doesn't blow
        // the locked tile height.
        if (title != null) {
            Text(
                text = title,
                typography = AppTheme.typography.Body.B400,
                color = com.dangerfield.cards.libraries.ui.system.color.ColorResource.FromColor(PokerPalette.ChipGold, "ChipGold"),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        VerticalSpacerD300()
        Text(
            text = formatCompactChips(seat.stack),
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onStackClick),
        )
        // Show the chip contribution (gold pill) OR the last-action label, not
        // both — they overlap in meaning ("Call 30" + a 30-chip pill duplicates
        // info) and stacking them blew the column past the locked row height
        // and clipped the bottom of the pill. Chip pill wins when there's a
        // contribution; the text fills in for fold/check/all-in.
        when {
            seat.contributedThisStreet > 0 -> {
                VerticalSpacerD100()
                ChipPill(
                    amount = seat.contributedThisStreet,
                    onClick = { onBetPillClick("You", seat.contributedThisStreet) },
                )
            }
            seat.lastAction != null && !seat.isActing -> {
                VerticalSpacerD100()
                LastActionPill(
                    label = seat.lastAction.shortLabel(),
                    onClick = { onLastActionClick("You", seat.lastAction) },
                )
            }
        }
    }
}

internal const val AnonymousAvatarEmoji: String = "🃏"

// --------------------------------------------------------------------------
// Preview fixtures — file-private, kept tight so the previews stand alone
// without leaning on the larger fixture set in PlayPokerScreen.kt.
// --------------------------------------------------------------------------

private fun previewHumanSeat(
    stack: Long = 980,
    contributed: Long = 0,
    isActing: Boolean = false,
    participation: HandParticipation = HandParticipation.InHand,
    holeCards: List<Card> = listOf(
        Card(Rank.Ace, Suit.Spades),
        Card(Rank.King, Suit.Spades),
    ),
    lastAction: PlayerAction? = null,
    isDealer: Boolean = true,
    isSmallBlind: Boolean = false,
    isBigBlind: Boolean = false,
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
    participation = participation,
    seatEmpty = false,
    lastAction = lastAction,
    isDealer = isDealer,
    isSmallBlind = isSmallBlind,
    isBigBlind = isBigBlind,
)

private fun previewTable(
    seat: SeatView,
    street: BettingRound = BettingRound.Preflop,
    communityCards: List<Card> = emptyList(),
    humanHandLabel: String? = null,
    handResult: HandResultView? = null,
): TableUiState.Active = TableUiState.Active(
    street = street,
    communityCards = communityCards,
    pot = 60,
    potCommittedThisStreet = 60,
    seats = listOf(seat),
    actingSeatIndex = if (seat.isActing) seat.index else null,
    isHumanTurn = seat.isActing,
    humanLegalActions = null,
    humanHandLabel = humanHandLabel,
    handResult = handResult,
    smallBlind = 10,
    bigBlind = 20,
    handNumber = 1,
    buttonSeatIndex = 0,
    smallBlindSeatIndex = null,
    bigBlindSeatIndex = null,
)

@Preview
@Composable
private fun PlayerAreaPreview_YourTurn() {
    PreviewContent {
        PlayerArea(table = previewTable(seat = previewHumanSeat(isActing = true)))
    }
}

@Preview
@Composable
private fun PlayerAreaPreview_WithChipPill() {
    PreviewContent {
        PlayerArea(
            table = previewTable(
                seat = previewHumanSeat(stack = 960, contributed = 20),
            ),
        )
    }
}

@Preview
@Composable
private fun PlayerAreaPreview_AfterCheck() {
    PreviewContent {
        PlayerArea(
            table = previewTable(
                seat = previewHumanSeat(lastAction = PlayerAction.Check),
                street = BettingRound.Flop,
                communityCards = listOf(
                    Card(Rank.Ten, Suit.Hearts),
                    Card(Rank.Seven, Suit.Clubs),
                    Card(Rank.Two, Suit.Diamonds),
                ),
                humanHandLabel = "High card · Ace",
            ),
        )
    }
}

@Preview
@Composable
private fun PlayerAreaPreview_Folded() {
    PreviewContent {
        PlayerArea(
            table = previewTable(
                seat = previewHumanSeat(
                    participation = HandParticipation.Folded,
                    lastAction = PlayerAction.Fold,
                ),
            ),
        )
    }
}

@Preview
@Composable
private fun PlayerAreaPreview_Winner() {
    PreviewContent {
        PlayerArea(
            table = previewTable(
                seat = previewHumanSeat(stack = 1200),
                street = BettingRound.River,
                communityCards = listOf(
                    Card(Rank.Ace, Suit.Hearts),
                    Card(Rank.King, Suit.Diamonds),
                    Card(Rank.Two, Suit.Clubs),
                    Card(Rank.Five, Suit.Hearts),
                    Card(Rank.Nine, Suit.Spades),
                ),
                humanHandLabel = "Two pair · Aces & Kings",
                handResult = HandResultView(
                    winners = listOf(
                        HandWinner(seatIndex = 0, amount = 240, handRank = null, byFold = false),
                    ),
                    board = emptyList(),
                ),
            ),
        )
    }
}
