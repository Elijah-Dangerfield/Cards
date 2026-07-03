package com.dangerfield.cards.features.room.impl.ui

import com.dangerfield.cards.features.room.impl.HandResultView
import com.dangerfield.cards.features.room.impl.TableUiState

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_board_rankings_a11y
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandEvaluator
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.formatCompactChips
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardBack
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD500
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/** How much each board card overlaps the previous, as a fraction of its own width. */
private const val BoardOverlapFraction = 0.34f

/** Stagger between each face-down board card dropping into place at hand start — the snake-like cascade. */
private const val BoardAppearStaggerMs = 70

/** Stagger between cards flipping face-up within a street (so the flop's three reveal in sequence). */
private const val BoardFlipStaggerMs = 130

@Composable
internal fun BoardArea(
    table: TableUiState.Active,
    onPotClick: () -> Unit = {},
    onBoardClick: () -> Unit = {},
) {
    val rankingsLabel = stringResource(Res.string.room_board_rankings_a11y)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // Size the five cards to fill the felt width rather than hardcoding their
        // dimensions: each card overlaps the previous by [BoardOverlapFraction] of
        // its own width, so width = available / (5 - 4·fraction) and the row spans
        // exactly the space. Height follows from the card aspect ratio.
        // Tapping the board opens the hand-rankings reference (moved off the top bar).
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .clickable(onClickLabel = rankingsLabel, onClick = onBoardClick),
        ) {
            val cardWidth = maxWidth / (5f - 4f * BoardOverlapFraction)
            val cardSize = PlayingCardSize.ofWidth(cardWidth)
            val overlap = cardWidth * BoardOverlapFraction
            // Reset every slot's animation state per hand so the face-down backs
            // cascade in again at the start of each new deal.
            key(table.handNumber) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(-overlap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (i in 0 until 5) {
                        BoardSlot(
                            card = table.communityCards.getOrNull(i),
                            appearDelayMs = i * BoardAppearStaggerMs,
                            flipDelayMs = (if (i < 3) i else 0) * BoardFlipStaggerMs,
                            size = cardSize,
                        )
                    }
                }
            }
        }
        // Ship the pot: once the hand resolves, drain the pot pill from the awarded
        // total down to 0 as the chips move to the winner (whose avatar already
        // glows). Mid-hand the pill tracks the live pot. In a preview the chips
        // sit at the full total so the moment is legible without a running clock.
        val shippedTotal = table.handResult?.winners?.sumOf { it.amount } ?: 0L
        val shipping = table.handResult != null && shippedTotal > 0
        val inPreview = LocalInspectionMode.current
        val shipAnim = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(table.handNumber, shipping, shippedTotal) {
            if (shipping && !inPreview) {
                shipAnim.snapTo(shippedTotal.toFloat())
                shipAnim.animateTo(0f, animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing))
            }
        }
        val potAmount = when {
            !shipping -> table.pot
            inPreview -> shippedTotal
            else -> shipAnim.value.toLong()
        }
        if (potAmount > 0) {
            VerticalSpacerD500()
            PotAmount(amount = potAmount, onClick = onPotClick)
        }
    }
}

/**
 * The pot as a plain number under the board — a small gold coin + the amount, no
 * pill, no "Pot" label. Minimal by design (the reference shows just the figure);
 * still tappable for the pot explainer.
 */
@Composable
private fun PotAmount(amount: Long, onClick: () -> Unit) {
    // Publish the pot's bounds so the pot-ship coins know where to launch from.
    val anchors = LocalTableRewardAnchors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .then(
                if (anchors != null) {
                    Modifier.onGloballyPositioned { anchors.potBounds = it.boundsInRoot() }
                } else {
                    Modifier
                },
            )
            .clip(Radii.Round.shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(Radii.Round.shape)
                .background(AppTheme.colors.poker.chipGold.color),
        )
        Text(
            text = formatCompactChips(amount),
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.content,
        )
    }
}

/**
 * One of the five board positions. At hand start every slot is a face-down back
 * that drops into place on a per-index stagger (the snake-like cascade). When the
 * slot's card becomes known it flips face-up in place — no drop from above. State
 * is reset per hand by the [key] on the hand number in [BoardArea].
 */
@Composable
private fun BoardSlot(
    card: Card?,
    appearDelayMs: Int,
    flipDelayMs: Int,
    size: PlayingCardSize,
) {
    // Previews don't drive animations to completion, so jump to the settled state.
    val skip = LocalInspectionMode.current

    var appeared by remember { mutableStateOf(skip) }
    LaunchedEffect(Unit) {
        if (!skip) delay(appearDelayMs.toLong())
        appeared = true
    }
    val appear by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "board-appear",
    )

    var flipped by remember { mutableStateOf(skip && card != null) }
    LaunchedEffect(card != null) {
        if (card != null && !flipped) {
            if (!skip) delay(flipDelayMs.toLong())
            flipped = true
        }
    }
    val flip by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(380),
        label = "board-flip",
    )

    Box(
        modifier = Modifier
            .size(width = size.width, height = size.height)
            .graphicsLayer {
                translationY = (1f - appear) * (-18).dp.toPx()
                alpha = appear
                val s = 0.8f + 0.2f * appear
                scaleX = s
                scaleY = s
                rotationY = flip
                cameraDistance = 12f * density
            },
    ) {
        if (flip <= 90f) {
            PlayingCardBack(size = size)
        } else {
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
                PlayingCard(card = card!!, size = size)
            }
        }
    }
}


@Preview
@Composable
private fun BoardAreaPreview_Preflop() {
    PreviewContent {
        BoardArea(table = PreviewSamples.activeTable(pot = 30))
    }
}

@Preview
@Composable
private fun BoardAreaPreview_Flop() {
    PreviewContent {
        BoardArea(
            table = PreviewSamples.activeTable(
                street = BettingRound.Flop,
                communityCards = PreviewSamples.flopBoard(),
                pot = 120,
            ),
        )
    }
}

@Preview
@Composable
private fun BoardAreaPreview_Turn() {
    PreviewContent {
        BoardArea(
            table = PreviewSamples.activeTable(
                street = BettingRound.Turn,
                communityCards = PreviewSamples.turnBoard(),
                pot = 320,
            ),
        )
    }
}

@Preview
@Composable
private fun BoardAreaPreview_Showdown() {
    // Showdown with the pot pill draining to the winner. All five board cards
    // render at full strength — the board no longer dims non-winning cards.
    val board = listOf(
        Card(Rank.Ten, Suit.Hearts),
        Card(Rank.Jack, Suit.Hearts),
        Card(Rank.Queen, Suit.Hearts),
        Card(Rank.Three, Suit.Clubs),
        Card(Rank.Seven, Suit.Spades),
    )
    val winnerHole = listOf(Card(Rank.Ace, Suit.Hearts), Card(Rank.King, Suit.Hearts))
    PreviewContent {
        BoardArea(
            table = PreviewSamples.activeTable(
                street = BettingRound.Complete,
                communityCards = board,
                pot = 480,
                handResult = HandResultView(
                    winners = listOf(
                        HandWinner(
                            seatIndex = 1,
                            amount = 480,
                            handRank = HandEvaluator.evaluate(winnerHole + board),
                            byFold = false,
                        ),
                    ),
                    board = board,
                ),
            ),
        )
    }
}

@Preview
@Composable
private fun BoardAreaPreview_River_BigPot() {
    PreviewContent {
        BoardArea(
            table = PreviewSamples.activeTable(
                street = BettingRound.River,
                communityCards = PreviewSamples.riverBoard(),
                pot = 1_840,
            ),
        )
    }
}
