package com.dangerfield.cards.features.room.impl.ui

import com.dangerfield.cards.features.room.impl.HandResultView
import com.dangerfield.cards.features.room.impl.SeatView
import com.dangerfield.cards.features.room.impl.TableUiState

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.ChipCoinAmount
import com.dangerfield.cards.libraries.ui.components.formatCompactChips
import com.dangerfield.cards.libraries.ui.components.poker.BlindMarker
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.poker.TurnCountdownRing
import com.dangerfield.cards.libraries.ui.components.poker.WinnerGlow
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.horizontalFadingEdge
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD100
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
internal fun OpponentsRow(
    table: TableUiState.Active,
    onBlindClick: () -> Unit = {},
    onLastActionClick: (seatName: String, action: PlayerAction) -> Unit = { _, _ -> },
    onAvatarTap: (SeatView) -> Unit = {},
) {
    val opponents = table.seats.filter { !it.isHuman }
    // Winner seat → amount won, so each seat can show both the glow and a "+N won"
    // badge that names the take. A split pot sums a seat's slices.
    val winnerAmounts = table.handResult?.winners
        ?.groupBy { it.seatIndex }
        ?.mapValues { (_, w) -> w.sumOf { it.amount } }
        .orEmpty()
    // At showdown the stack/bet slot under each still-in opponent morphs into
    // their revealed hole cards until the next hand deals — the felt-native
    // showdown that replaces the old full-screen dialog on money games. The
    // projection only populates an opponent's holeCards once the hand reaches a
    // reveal, so the per-seat gate reads the cards directly.
    val handComplete = table.handResult != null
    // Turn token the countdown ring re-arms on — only the acting seat reads
    // it, and only when the table enforces a timer (MP). Null on solo tables
    // suppresses the ring entirely. See [TableUiState.Active.turnSequence].
    val turnTimerSeconds = table.turnTimerSeconds
    val turnKey = table.handNumber to table.turnSequence
    if (opponents.size > PackedOpponentLimit) {
        ScrollingOpponentsRow(
            opponents = opponents,
            winnerAmounts = winnerAmounts,
            handComplete = handComplete,
            actingSeatIndex = table.actingSeatIndex,
            turnTimerSeconds = turnTimerSeconds,
            turnKey = turnKey,
            onBlindClick = onBlindClick,
            onLastActionClick = onLastActionClick,
            onAvatarTap = onAvatarTap,
        )
    } else {
        PackedOpponentsRow(
            opponents = opponents,
            winnerAmounts = winnerAmounts,
            handComplete = handComplete,
            turnTimerSeconds = turnTimerSeconds,
            turnKey = turnKey,
            onBlindClick = onBlindClick,
            onLastActionClick = onLastActionClick,
            onAvatarTap = onAvatarTap,
        )
    }
}

private const val PackedOpponentLimit = 4

@Composable
private fun PackedOpponentsRow(
    opponents: List<SeatView>,
    winnerAmounts: Map<Int, Long>,
    handComplete: Boolean,
    turnTimerSeconds: Int?,
    turnKey: Any,
    onBlindClick: () -> Unit,
    onLastActionClick: (seatName: String, action: PlayerAction) -> Unit,
    onAvatarTap: (SeatView) -> Unit = {},
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val rowWidth = maxWidth
        val count = opponents.size.coerceAtLeast(1)
        val perOpponent = (rowWidth / count).coerceAtLeast(48.dp)
        val avatarSize = (perOpponent * 0.5f).coerceIn(34.dp, 56.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            opponents.forEach { seat ->
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    OpponentSeat(
                        seat = seat,
                        isWinner = seat.index in winnerAmounts,
                        winAmount = winnerAmounts[seat.index] ?: 0L,
                        handComplete = handComplete,
                        avatarSize = avatarSize,
                        turnTimerSeconds = turnTimerSeconds,
                        turnKey = turnKey,
                        onBlindClick = onBlindClick,
                        onLastActionClick = onLastActionClick,
                        onAvatarTap = { onAvatarTap(seat) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScrollingOpponentsRow(
    opponents: List<SeatView>,
    winnerAmounts: Map<Int, Long>,
    handComplete: Boolean,
    actingSeatIndex: Int?,
    turnTimerSeconds: Int?,
    turnKey: Any,
    onBlindClick: () -> Unit,
    onLastActionClick: (seatName: String, action: PlayerAction) -> Unit,
    onAvatarTap: (SeatView) -> Unit = {},
) {
    val listState = rememberLazyListState()
    var suppressAutoScroll by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling) {
                    suppressAutoScroll = true
                } else {
                    delay(ManualScrollGraceMillis)
                    suppressAutoScroll = false
                }
            }
    }

    LaunchedEffect(actingSeatIndex, opponents) {
        if (actingSeatIndex == null || suppressAutoScroll) return@LaunchedEffect
        val target = opponents.indexOfFirst { it.index == actingSeatIndex }
        if (target < 0) return@LaunchedEffect
        // Center the acting seat in the viewport rather than pinning it
        // to the start. `animateScrollToItem`'s default puts the target
        // at scrollOffset 0 (left edge); the negative offset pulls the
        // target inward by half the empty space. Falls back to a plain
        // scroll if layout isn't ready (first composition).
        val info = listState.layoutInfo
        val viewportWidth = info.viewportEndOffset - info.viewportStartOffset
        val itemWidth = info.visibleItemsInfo.firstOrNull()?.size ?: 0
        val centeringOffset = if (viewportWidth > 0 && itemWidth > 0) {
            -((viewportWidth - itemWidth) / 2)
        } else {
            0
        }
        listState.animateScrollToItem(target, centeringOffset)
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalFadingEdge(listState),
        // Small top padding so the top-left position badge has a hair of
        // room to bleed past the avatar without LazyRow clipping it.
        contentPadding = PaddingValues(
            start = ScrollingRowHorizontalPadding,
            end = ScrollingRowHorizontalPadding,
            top = ScrollingRowOverhangPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(ScrollingRowItemSpacing),
        verticalAlignment = Alignment.Top,
    ) {
        items(
            count = opponents.size,
            key = { i -> opponents[i].index },
        ) { i ->
            val seat = opponents[i]
            Box(
                modifier = Modifier.width(ScrollingSeatWidth),
                contentAlignment = Alignment.TopCenter,
            ) {
                OpponentSeat(
                    seat = seat,
                    isWinner = seat.index in winnerAmounts,
                    winAmount = winnerAmounts[seat.index] ?: 0L,
                    handComplete = handComplete,
                    avatarSize = ScrollingAvatarSize,
                    turnTimerSeconds = turnTimerSeconds,
                    turnKey = turnKey,
                    onBlindClick = onBlindClick,
                    onLastActionClick = onLastActionClick,
                    onAvatarTap = { onAvatarTap(seat) },
                )
            }
        }
    }
}

private const val ManualScrollGraceMillis = 3000L
private val ScrollingAvatarSize: Dp = 46.dp
private val ScrollingSeatWidth: Dp = 60.dp
private val ScrollingRowItemSpacing: Dp = 8.dp
private val ScrollingRowHorizontalPadding: Dp = 12.dp
private val ScrollingRowOverhangPadding: Dp = 6.dp

@Composable
private fun OpponentSeat(
    seat: SeatView,
    isWinner: Boolean,
    winAmount: Long,
    handComplete: Boolean,
    avatarSize: Dp,
    turnTimerSeconds: Int?,
    turnKey: Any,
    onBlindClick: () -> Unit,
    onLastActionClick: (seatName: String, action: PlayerAction) -> Unit,
    onAvatarTap: () -> Unit = {},
) {
    val folded = seat.participation == HandParticipation.Folded
    val busted = seat.isBusted
    val outOfHand = folded || busted
    val hasBlindRole = seat.isDealer || seat.isSmallBlind || seat.isBigBlind
    // The gold turn / aggressor ring (and the countdown arc) render in the thin
    // band between the avatar's edge and the ring box edge.
    val ringSize = avatarSize + 6.dp
    // A still-in seat that has already acted this street is gently scrimmed so
    // attention stays on who still has to act — short of the heavier fold/bust
    // fade. Never at showdown (every seat has a last action then).
    val actedThisStreet = !handComplete && !outOfHand && !seat.isActing &&
        seat.participation == HandParticipation.InHand && seat.lastAction != null
    val dimAlpha = when {
        busted -> 0.4f
        folded -> 0.45f
        actedThisStreet -> 0.72f
        else -> 1f
    }
    val dimMod = Modifier.alpha(dimAlpha)
    // The seat on the clock sits at full size; everyone else shrinks a touch so
    // attention pulls to whoever's acting. Draw-only scale, so the row never reflows.
    val seatScale by animateFloatAsState(
        targetValue = if (seat.isActing) 1f else 0.92f,
        animationSpec = tween(220),
        label = "seat-scale",
    )
    val rewardAnchors = LocalTableRewardAnchors.current

    // Ring zone: the depleting countdown (timer-enforced human), a pulsing gold
    // "to act" ring (bots / solo, untimed), or the solid gold aggressor ring after
    // a bet/raise/all-in. All gold, mutually exclusive — never two on one seat.
    val countdownSeconds = turnTimerSeconds?.takeIf { seat.isActing && !seat.isBot && !outOfHand }
    val aggressor = !outOfHand && !handComplete && seat.lastAction.isAggressive()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .graphicsLayer {
                scaleX = seatScale
                scaleY = seatScale
            },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(ringSize)
                .then(
                    if (rewardAnchors != null) {
                        Modifier.onGloballyPositioned {
                            rewardAnchors.seatAvatarBounds[seat.index] = it.boundsInRoot()
                        }
                    } else {
                        Modifier
                    },
                )
                .clickable(onClick = onAvatarTap),
        ) {
            when {
                countdownSeconds != null -> TurnCountdownRing(
                    turnKey = turnKey,
                    durationSeconds = countdownSeconds,
                    modifier = Modifier.size(ringSize),
                )
                !outOfHand && seat.isActing -> GoldSeatRing(pulsing = true, modifier = Modifier.size(ringSize))
                aggressor -> GoldSeatRing(pulsing = false, modifier = Modifier.size(ringSize))
            }
            if (isWinner) WinnerGlow(modifier = Modifier.size(ringSize))

            // The avatar — recolored (dimmed) when out of the hand.
            Box(modifier = dimMod) {
                AvatarCircle(
                    name = seat.displayName,
                    size = avatarSize,
                    emoji = seat.emoji,
                    backgroundColorHex = seat.avatarBackgroundColorHex,
                )
            }

            // Out state OR the bottom-center action chip for this round — one zone,
            // never both (a folded/busted seat has no live action).
            when {
                busted -> Text(
                    text = "✕",
                    typography = AppTheme.typography.Heading.H800,
                    color = AppTheme.colors.danger,
                    modifier = Modifier.align(Alignment.Center),
                )
                folded -> MuckedCardsMarker(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 5.dp),
                )
                !handComplete -> seat.lastAction?.let { action ->
                    SeatActionChip(
                        action = action,
                        onClick = { onLastActionClick(seat.displayName, action) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = 7.dp),
                    )
                }
            }

            // Position badge — top-left, persists the whole hand.
            BlindMarker(
                isDealer = seat.isDealer,
                isSmallBlind = seat.isSmallBlind,
                isBigBlind = seat.isBigBlind,
                muted = outOfHand,
                onClick = if (hasBlindRole) onBlindClick else null,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
        // Clearance for the action chip that straddles the avatar's bottom edge.
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = seat.displayName,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.content,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = dimMod,
        )
        // At showdown the under-avatar slot reveals this seat's two hole cards in
        // place of the stack. A folded (mucked) seat reveals nothing.
        val revealHoleCards = handComplete && seat.holeCards.size == 2
        if (revealHoleCards) {
            VerticalSpacerD100()
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = dimMod,
            ) {
                seat.holeCards.forEach { card ->
                    PlayingCard(card = card, size = PlayingCardSize.Mini)
                }
            }
        } else {
            AnimatedVisibility(
                visible = !busted,
                enter = fadeIn(animationSpec = tween(220)) +
                    slideInVertically(animationSpec = tween(220)) { -it / 2 } +
                    expandVertically(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(280)) +
                    slideOutVertically(animationSpec = tween(360)) { it } +
                    shrinkVertically(animationSpec = tween(360)),
            ) {
                ChipCoinAmount(
                    amount = seat.stack,
                    coinSize = 12.dp,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.contentSecondary,
                    gap = 4.dp,
                    formatter = ::formatCompactChips,
                    modifier = dimMod,
                )
            }
        }
        // The winner's take, named in gold under the seat for the whole showdown
        // window — the persistent companion to the (transient) coins that flew here.
        if (isWinner && handComplete && winAmount > 0) {
            VerticalSpacerD100()
            WinAmountBadge(amount = winAmount)
        }
    }
}

private fun PlayerAction?.isAggressive(): Boolean =
    this is PlayerAction.Bet || this is PlayerAction.Raise || this is PlayerAction.AllIn

@Composable
private fun WinAmountBadge(amount: Long) {
    Text(
        text = "+${formatCompactChips(amount)}",
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.poker.chipGold,
    )
}

/**
 * The gold ring that wraps an avatar — solid for an aggressor (bet/raise/all-in),
 * or a slow pulse for an untimed "to act" seat (bots / solo, where there's no
 * server clock to deplete). The timer-enforced countdown uses [TurnCountdownRing].
 */
@Composable
private fun GoldSeatRing(pulsing: Boolean, modifier: Modifier = Modifier) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "to-act")
        val pulse by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "to-act-alpha",
        )
        pulse
    } else {
        1f
    }
    Box(
        modifier = modifier.border(
            width = 2.5.dp,
            color = AppTheme.colors.poker.chipGold.color.copy(alpha = alpha),
            shape = CircleShape,
        ),
    )
}

/**
 * The bottom-center action chip: green check for a check, a neutral pill for a
 * call, a gold pill for chips going in (bet/raise/all-in). Gold == chips in.
 */
@Composable
private fun SeatActionChip(
    action: PlayerAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (action) {
        is PlayerAction.Check -> CheckBadge(onClick = onClick, modifier = modifier)
        is PlayerAction.Call -> ActionPill(
            text = formatCompactChips(action.amount),
            gold = false,
            onClick = onClick,
            modifier = modifier,
        )
        is PlayerAction.Bet -> ActionPill(
            text = formatCompactChips(action.amount),
            gold = true,
            onClick = onClick,
            modifier = modifier,
        )
        is PlayerAction.Raise -> ActionPill(
            text = "▲ ${formatCompactChips(action.totalStreetContribution)}",
            gold = true,
            onClick = onClick,
            modifier = modifier,
        )
        is PlayerAction.AllIn -> ActionPill(
            text = "ALL-IN",
            gold = true,
            onClick = onClick,
            modifier = modifier,
        )
        is PlayerAction.Fold -> Unit
    }
}

@Composable
private fun CheckBadge(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(AppTheme.colors.success.color)
            .border(2.dp, AppTheme.colors.background.color, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "✓",
            typography = AppTheme.typography.Label.L300,
            color = AppTheme.colors.onSuccess,
        )
    }
}

@Composable
private fun ActionPill(
    text: String,
    gold: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(Radii.Round.shape)
            .background(if (gold) AppTheme.colors.poker.chipGold.color else AppTheme.colors.surfaceHigh.color)
            .then(
                if (gold) Modifier else Modifier.border(1.dp, AppTheme.colors.border.color, Radii.Round.shape),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            typography = AppTheme.typography.Label.L500,
            color = if (gold) AppTheme.colors.background else AppTheme.colors.content,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Two tilted grey rects under a folded seat — the mucked hand. */
@Composable
private fun MuckedCardsMarker(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy((-5).dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        MuckCard(rotation = -12f)
        MuckCard(rotation = 12f)
    }
}

@Composable
private fun MuckCard(rotation: Float) {
    Box(
        modifier = Modifier
            .graphicsLayer { rotationZ = rotation }
            .size(width = 11.dp, height = 15.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(AppTheme.colors.surfaceHigh.color)
            .border(1.dp, AppTheme.colors.background.color, RoundedCornerShape(2.dp)),
    )
}

@Preview
@Composable
private fun OpponentsRowPreview_HeadsUp() {
    PreviewContent {
        OpponentsRow(
            table = PreviewSamples.activeTable(
                seats = listOf(
                    PreviewSamples.humanSeat(isDealer = false),
                    PreviewSamples.botSeat(index = 1, name = "Jane", isActing = true, isDealer = true),
                ),
                actingSeatIndex = 1,
            ),
        )
    }
}

@Preview
@Composable
private fun OpponentsRowPreview_HumanOpponentCountdown() {
    // MP table (turnTimerSeconds set) with a human opponent on the clock —
    // their avatar shows the depleting countdown ring instead of the plain
    // pulsing halo a bot seat would get.
    PreviewContent {
        OpponentsRow(
            table = PreviewSamples.activeTable(
                seats = listOf(
                    PreviewSamples.humanSeat(isDealer = false),
                    PreviewSamples.botSeat(index = 1, name = "Priya", isActing = true, isDealer = true)
                        .copy(isBot = false, emoji = "🙂"),
                ),
                actingSeatIndex = 1,
                turnTimerSeconds = 30,
            ),
        )
    }
}

@Preview
@Composable
private fun OpponentsRowPreview_FourSeats_MidHand() {
    PreviewContent {
        OpponentsRow(
            table = PreviewSamples.activeTable(
                seats = listOf(
                    PreviewSamples.humanSeat(),
                    PreviewSamples.botSeat(
                        index = 1,
                        name = "David",
                        isSmallBlind = true,
                        contributed = 20,
                        lastAction = PlayerAction.Call(amount = 20),
                    ),
                    PreviewSamples.botSeat(
                        index = 2,
                        name = "Jane",
                        isBigBlind = true,
                        contributed = 60,
                        isActing = true,
                        lastAction = PlayerAction.Raise(totalStreetContribution = 60, raiseAmount = 40),
                    ),
                    PreviewSamples.botSeat(
                        index = 3,
                        name = "Mike",
                        participation = HandParticipation.Folded,
                        lastAction = PlayerAction.Fold,
                    ),
                ),
                actingSeatIndex = 2,
            ),
        )
    }
}

@Preview
@Composable
private fun OpponentsRowPreview_SixSeats() {
    PreviewContent {
        OpponentsRow(
            table = PreviewSamples.activeTable(
                seats = listOf(
                    PreviewSamples.humanSeat(),
                    PreviewSamples.botSeat(index = 1, name = "David", isSmallBlind = true),
                    PreviewSamples.botSeat(index = 2, name = "Jane", isBigBlind = true),
                    PreviewSamples.botSeat(index = 3, name = "Mike", isActing = true),
                    PreviewSamples.botSeat(index = 4, name = "Gina"),
                    PreviewSamples.botSeat(index = 5, name = "Steve", participation = HandParticipation.Folded),
                ),
                actingSeatIndex = 3,
            ),
        )
    }
}

@Preview
@Composable
private fun OpponentsRowPreview_NineSeats_Scrolling() {
    PreviewContent {
        OpponentsRow(
            table = PreviewSamples.activeTable(
                seats = listOf(
                    PreviewSamples.humanSeat(),
                    PreviewSamples.botSeat(index = 1, name = "David", isSmallBlind = true),
                    PreviewSamples.botSeat(index = 2, name = "Jane", isBigBlind = true),
                    PreviewSamples.botSeat(index = 3, name = "Mike"),
                    PreviewSamples.botSeat(index = 4, name = "Gina"),
                    PreviewSamples.botSeat(index = 5, name = "Steve", isActing = true),
                    PreviewSamples.botSeat(index = 6, name = "Otto"),
                    PreviewSamples.botSeat(index = 7, name = "Lex", participation = HandParticipation.Folded),
                    PreviewSamples.botSeat(index = 8, name = "Iris"),
                ),
                actingSeatIndex = 5,
            ),
        )
    }
}

@Preview
@Composable
private fun OpponentsRowPreview_BustedOpponent() {
    PreviewContent {
        OpponentsRow(
            table = PreviewSamples.activeTable(
                seats = listOf(
                    PreviewSamples.humanSeat(),
                    PreviewSamples.botSeat(
                        index = 1,
                        name = "David",
                        stack = 0,
                        participation = HandParticipation.AllIn,
                        holeCards = listOf(
                            PreviewSamples.card(com.dangerfield.cards.libraries.gameplay.Rank.King, com.dangerfield.cards.libraries.gameplay.Suit.Spades),
                            PreviewSamples.card(com.dangerfield.cards.libraries.gameplay.Rank.King, com.dangerfield.cards.libraries.gameplay.Suit.Diamonds),
                        ),
                        lastAction = PlayerAction.AllIn(amount = 1_000),
                    ),
                    PreviewSamples.botSeat(
                        index = 2,
                        name = "Jane",
                        stack = 2_000,
                        holeCards = listOf(
                            PreviewSamples.card(com.dangerfield.cards.libraries.gameplay.Rank.Ace, com.dangerfield.cards.libraries.gameplay.Suit.Hearts),
                            PreviewSamples.card(com.dangerfield.cards.libraries.gameplay.Rank.Ace, com.dangerfield.cards.libraries.gameplay.Suit.Clubs),
                        ),
                        lastAction = PlayerAction.Call(amount = 1_000),
                    ),
                ),
                actingSeatIndex = null,
                handResult = HandResultView(
                    winners = listOf(PreviewSamples.handWinner(seatIndex = 2, amount = 2_000)),
                    board = emptyList(),
                ),
            ),
        )
    }
}

@Preview
@Composable
private fun OpponentsRowPreview_Winner() {
    PreviewContent {
        OpponentsRow(
            table = PreviewSamples.activeTable(
                seats = listOf(
                    PreviewSamples.humanSeat(participation = HandParticipation.Folded),
                    PreviewSamples.botSeat(index = 1, name = "Jane", stack = 1_240),
                    PreviewSamples.botSeat(index = 2, name = "David", participation = HandParticipation.Folded),
                ),
                actingSeatIndex = null,
                handResult = HandResultView(
                    winners = listOf(PreviewSamples.handWinner(seatIndex = 1)),
                    board = emptyList(),
                ),
            ),
        )
    }
}
