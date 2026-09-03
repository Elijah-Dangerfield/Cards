package com.dangerfield.cards.features.room.impl.ui

import com.dangerfield.cards.features.room.impl.HandResultView
import com.dangerfield.cards.features.room.impl.SeatView
import com.dangerfield.cards.features.room.impl.TableUiState

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.horizontalFadingEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.tooling.preview.Preview

@Composable
internal fun OpponentsRow(
    table: TableUiState.Active,
    onBlindClick: () -> Unit = {},
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
    val deadlineEpochMs = table.turnDeadlineEpochMs
    if (opponents.size > PackedOpponentLimit) {
        ScrollingOpponentsRow(
            opponents = opponents,
            winnerAmounts = winnerAmounts,
            handComplete = handComplete,
            actingSeatIndex = table.actingSeatIndex,
            turnTimerSeconds = turnTimerSeconds,
            turnKey = turnKey,
            deadlineEpochMs = deadlineEpochMs,
            onBlindClick = onBlindClick,
            onAvatarTap = onAvatarTap,
        )
    } else {
        PackedOpponentsRow(
            opponents = opponents,
            winnerAmounts = winnerAmounts,
            handComplete = handComplete,
            turnTimerSeconds = turnTimerSeconds,
            turnKey = turnKey,
            deadlineEpochMs = deadlineEpochMs,
            onBlindClick = onBlindClick,
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
    deadlineEpochMs: Long? = null,
    onBlindClick: () -> Unit,
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
                        deadlineEpochMs = deadlineEpochMs,
                        onBlindClick = onBlindClick,
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
    deadlineEpochMs: Long? = null,
    onBlindClick: () -> Unit,
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
                    deadlineEpochMs = deadlineEpochMs,
                    onBlindClick = onBlindClick,
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
