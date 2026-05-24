package com.dangerfield.cards.features.room.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
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
import com.dangerfield.cards.libraries.ui.components.poker.BustedStamp
import com.dangerfield.cards.libraries.ui.components.poker.ChipPill
import com.dangerfield.cards.libraries.ui.components.poker.LastActionPill
import com.dangerfield.cards.libraries.ui.components.poker.PulsingActiveRing
import com.dangerfield.cards.libraries.ui.components.poker.WinnerGlow
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.horizontalFadingEdge
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD100
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
internal fun OpponentsRow(
    table: TableUiState.Active,
    onBlindClick: () -> Unit = {},
    onBetPillClick: (seatName: String, amount: Long) -> Unit = { _, _ -> },
    onLastActionClick: (seatName: String, action: PlayerAction) -> Unit = { _, _ -> },
    onAvatarTap: (SeatView) -> Unit = {},
) {
    val opponents = table.seats.filter { !it.isHuman }
    val winners = table.handResult?.winners?.map { it.seatIndex }?.toSet().orEmpty()
    if (opponents.size > PackedOpponentLimit) {
        ScrollingOpponentsRow(
            opponents = opponents,
            winners = winners,
            actingSeatIndex = table.actingSeatIndex,
            onBlindClick = onBlindClick,
            onBetPillClick = onBetPillClick,
            onLastActionClick = onLastActionClick,
            onAvatarTap = onAvatarTap,
        )
    } else {
        PackedOpponentsRow(
            opponents = opponents,
            winners = winners,
            onBlindClick = onBlindClick,
            onBetPillClick = onBetPillClick,
            onLastActionClick = onLastActionClick,
            onAvatarTap = onAvatarTap,
        )
    }
}

private const val PackedOpponentLimit = 4

@Composable
private fun PackedOpponentsRow(
    opponents: List<SeatView>,
    winners: Set<Int>,
    onBlindClick: () -> Unit,
    onBetPillClick: (seatName: String, amount: Long) -> Unit,
    onLastActionClick: (seatName: String, action: PlayerAction) -> Unit,
    onAvatarTap: (SeatView) -> Unit = {},
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val rowWidth = maxWidth
        val count = opponents.size.coerceAtLeast(1)
        val perOpponent = (rowWidth / count).coerceAtLeast(56.dp)
        val avatarSize = (perOpponent * 0.62f).coerceIn(40.dp, 76.dp)
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
                        isWinner = seat.index in winners,
                        avatarSize = avatarSize,
                        onBlindClick = onBlindClick,
                        onBetPillClick = onBetPillClick,
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
    winners: Set<Int>,
    actingSeatIndex: Int?,
    onBlindClick: () -> Unit,
    onBetPillClick: (seatName: String, amount: Long) -> Unit,
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
        // Top padding gives the chevron (offset y=-16) and the last-
        // action pill (offset y=-6, pill height ~22dp) room to render
        // inside the row. Without it, LazyRow clips at its bounds and
        // the "Folded" / "Called 10" pill gets cut at the top edge —
        // the packed (non-scrolling) variant uses a plain Row, which
        // doesn't clip, so that path renders fine.
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
                    isWinner = seat.index in winners,
                    avatarSize = ScrollingAvatarSize,
                    onBlindClick = onBlindClick,
                    onBetPillClick = onBetPillClick,
                    onLastActionClick = onLastActionClick,
                )
            }
        }
    }
}

private const val ManualScrollGraceMillis = 3000L
private val ScrollingAvatarSize: Dp = 56.dp
private val ScrollingSeatWidth: Dp = 72.dp
private val ScrollingRowItemSpacing: Dp = 8.dp
private val ScrollingRowHorizontalPadding: Dp = 12.dp
// Headroom for the active-turn chevron (-16dp) and the last-action
// pill (-6dp offset, ~22dp tall = 28dp overhang). 28dp matches the
// pill's worst case; if the chevron grows or the pill gets a bigger
// font, bump this together.
private val ScrollingRowOverhangPadding: Dp = 28.dp

@Composable
private fun OpponentSeat(
    seat: SeatView,
    isWinner: Boolean,
    avatarSize: Dp,
    onBlindClick: () -> Unit,
    onBetPillClick: (seatName: String, amount: Long) -> Unit,
    onLastActionClick: (seatName: String, action: PlayerAction) -> Unit,
    onAvatarTap: () -> Unit = {},
) {
    val folded = seat.participation == HandParticipation.Folded
    val busted = !seat.seatEmpty &&
        seat.stack <= 0L &&
        seat.participation != HandParticipation.NotDealt
    val ringSize = avatarSize + 12.dp
    val hasBlindRole = seat.isDealer || seat.isSmallBlind || seat.isBigBlind
    val dimAlpha = when {
        busted -> 0.35f
        folded -> 0.4f
        else -> 1f
    }
    val dimMod = Modifier.alpha(dimAlpha)
    // The fade-when-folded effect is applied per-element rather than on the
    // outer Column. A wrapping `Modifier.alpha` rasterizes into an offscreen
    // layer sized to the wrapped bounds, which clips the LastActionPill's
    // negative-Y offset (the "Fold" chip floats above the avatar's top edge
    // and was getting cut off). Splitting the dim keeps the pill out of the
    // clipped region while still fading the avatar + identity text.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(ringSize)
                .clickable(onClick = onAvatarTap),
        ) {
            // Faded subtree — everything that's "the seat" semantically.
            Box(modifier = Modifier.size(ringSize).then(dimMod), contentAlignment = Alignment.Center) {
                if (seat.isActing) PulsingActiveRing(modifier = Modifier.size(ringSize))
                if (isWinner) WinnerGlow(modifier = Modifier.size(ringSize))
                AvatarCircle(
                    name = seat.displayName,
                    size = avatarSize,
                    emoji = seat.emoji,
                    backgroundColorHex = seat.avatarBackgroundColorHex,
                )
            }

            // Active-turn chevron — floats just above the avatar's outer ring.
            ChevronOverlay(
                visible = seat.isActing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-16).dp),
            )

            // Last-action pill — rendered OUTSIDE the dim scope so the "Fold"
            // pill doesn't get clipped by the offscreen layer.
            LastActionOverlay(
                action = seat.lastAction,
                onClick = { action -> onLastActionClick(seat.displayName, action) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-6).dp),
            )

            // Dealer / SB / BB chip — overlapping inward over the avatar's
            // bottom-right so the edge seats in the row don't clip the badge
            // against the screen margin.
            BlindMarker(
                isDealer = seat.isDealer,
                isSmallBlind = seat.isSmallBlind,
                isBigBlind = seat.isBigBlind,
                onClick = if (hasBlindRole) onBlindClick else null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-6).dp, y = (-6).dp),
            )

            if (busted) {
                BustedStamp(modifier = Modifier.align(Alignment.Center))
            }
        }
        VerticalSpacerD100()
        Text(
            text = seat.displayName,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = dimMod,
        )
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
                color = AppTheme.colors.textSecondary,
                gap = 4.dp,
                formatter = ::formatCompactChips,
                modifier = dimMod,
            )
        }
        if (seat.contributedThisStreet > 0) {
            VerticalSpacerD100()
            ChipPill(
                amount = seat.contributedThisStreet,
                onClick = { onBetPillClick(seat.displayName, seat.contributedThisStreet) },
                modifier = dimMod,
            )
        }
    }
}

/**
 * Extracted so the `AnimatedVisibility` call site sees only `BoxScope` and
 * resolves to the top-level overload. Inlining this into `OpponentSeat` puts
 * it inside an outer `Column { Box { ... } }`, which makes the `ColumnScope`
 * extension of `AnimatedVisibility` ambiguous with the top-level function.
 */
@Composable
private fun ChevronOverlay(visible: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(160)),
            exit = fadeOut(animationSpec = tween(140)),
        ) {
            Text(
                text = "▼",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.text,
            )
        }
    }
}

@Composable
private fun LastActionOverlay(
    action: PlayerAction?,
    onClick: (PlayerAction) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = action != null,
            enter = slideInVertically(animationSpec = tween(240)) { -it } +
                fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(animationSpec = tween(180)) { -it } +
                fadeOut(animationSpec = tween(140)),
        ) {
            action?.let { a -> LastActionPill(label = a.shortLabel(), onClick = { onClick(a) }) }
        }
    }
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
                        lastAction = PlayerAction.AllIn(amount = 1_000),
                    ),
                    PreviewSamples.botSeat(
                        index = 2,
                        name = "Jane",
                        stack = 2_000,
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
