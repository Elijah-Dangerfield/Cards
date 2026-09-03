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
import com.dangerfield.cards.libraries.ui.components.quantizeRollingNumber
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.tooling.preview.Preview

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
        // derivedStateOf so BoardArea's scope invalidates once per quantized
        // step (~12 over the 800ms ship) instead of once per frame. Reading
        // shipAnim.value directly here dragged the whole board — including the
        // BoxWithConstraints subcomposition — at 60fps, and fed PotAmount a
        // fresh string each time, which is the glyph-cache path from ENG-49.
        //
        // Keyed on everything the lambda captures. An unkeyed
        // remember { derivedStateOf { } } capturing a State that gets recreated
        // is what silently broke tap-to-flip; not repeating that.
        val potAmount by remember(shipping, inPreview, shippedTotal, table.pot) {
            derivedStateOf {
                when {
                    !shipping -> table.pot
                    inPreview -> shippedTotal
                    else -> quantizeRollingNumber(shipAnim.value.toLong(), shippedTotal, 0L)
                }
            }
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
    // Deliberately `State<Float>`, not `by`. Both values feed the graphicsLayer
    // below, which is a draw-phase lambda — unwrapping them here would
    // subscribe *composition* to them and recompose this slot on all ~37 frames
    // of the appear + flip tween, five slots at a time, for nothing. Same class
    // of mistake as ENG-49.
    val appear = animateFloatAsState(
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
    val flip = animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(380),
        label = "board-flip",
    )
    // The one thing composition genuinely needs from the flip is which face is
    // toward the viewer, and that changes exactly once. `derivedStateOf`
    // collapses the whole 380ms sweep into that single invalidation instead of
    // one per frame.
    val faceTowardViewer by remember { derivedStateOf { flip.value > 90f } }

    Box(
        modifier = Modifier
            .size(width = size.width, height = size.height)
            .graphicsLayer {
                val appeared = appear.value
                translationY = (1f - appeared) * (-18).dp.toPx()
                alpha = appeared
                val s = 0.8f + 0.2f * appeared
                scaleX = s
                scaleY = s
                rotationY = flip.value
                cameraDistance = 12f * density
            },
    ) {
        // `card == null` is part of the condition, not a !! below it. `flipped`
        // is only ever set true and never reset, and key(handNumber) only saves
        // us across hands — so a slot whose card goes non-null -> null *within*
        // one hand kept flip at 180f and dereferenced null, taking the whole
        // play screen down. Nothing emits that today, but the crash primitive
        // does not need to exist: a slot with no card is a back, whatever angle
        // it is at.
        if (!faceTowardViewer || card == null) {
            PlayingCardBack(size = size)
        } else {
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
                PlayingCard(card = card, size = size)
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
