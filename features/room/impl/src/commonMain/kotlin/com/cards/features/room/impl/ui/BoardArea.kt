package com.dangerfield.cards.features.room.impl.ui

import com.dangerfield.cards.features.room.impl.HandResultView
import com.dangerfield.cards.features.room.impl.TableUiState

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_board_pot_pill_label
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandEvaluator
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.formatCompactChips
import com.dangerfield.cards.libraries.ui.components.poker.LocalFeltAccentSurface
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardBack
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD600
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The board cards that compose the winning hand at showdown, derived from each
 * winner's evaluated [com.dangerfield.cards.libraries.gameplay.HandRank.bestFive].
 * Empty when the hand hasn't ended, was won by a fold (no showdown — nothing to
 * highlight), or no winner carries an evaluated rank. The board cards in this set
 * stay lit at showdown while the rest dim, so the hand that won reads at a glance.
 */
internal fun winningBoardCards(handResult: HandResultView?, communityCards: List<Card>): Set<Card> {
    if (handResult == null) return emptySet()
    if (handResult.winners.all { it.byFold }) return emptySet()
    val best = handResult.winners.flatMapTo(mutableSetOf()) { it.handRank?.bestFive.orEmpty() }
    if (best.isEmpty()) return emptySet()
    return communityCards.filterTo(mutableSetOf()) { it in best }
}

@Composable
internal fun BoardArea(table: TableUiState.Active, onPotClick: () -> Unit = {}) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        val cardSize = PlayingCardSize.Board
        val overlap = 28.dp
        // At showdown light the board cards that made the winning hand and dim the
        // rest; before showdown every dealt card renders at full strength.
        val winningCards = winningBoardCards(table.handResult, table.communityCards)
        // Five community cards, overlapping. A single outlined well sits behind
        // the row spanning the full 5-slot region — dealt cards cover their
        // portion of the well, undealt portions read as one unified "cards land
        // here" zone instead of five disconnected outlines.
        Box(contentAlignment = Alignment.Center) {
            BoardWell(
                width = cardSize.width * 5 - overlap * 4,
                height = cardSize.height,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(-overlap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in 0 until 5) {
                    val c = table.communityCards.getOrNull(i)
                    val streetIndexInStreet = if (i < 3) i else 0
                    if (c == null) {
                        // Undealt slot — a face-down back, not a blank gap. The five
                        // backs fill the well at hand start and flip to faces per
                        // street (the client only learns each card at its street, so
                        // an undealt slot stays a back until then).
                        PlayingCardBack(size = cardSize)
                    } else {
                        // Dim the losing board cards at showdown so the winning ones
                        // stand out; full strength otherwise.
                        val dimmed = winningCards.isNotEmpty() && c !in winningCards
                        Box(modifier = Modifier.alpha(if (dimmed) 0.3f else 1f)) {
                            BoardCard(
                                card = c,
                                revealDelayMs = streetIndexInStreet * 240,
                                size = cardSize,
                            )
                        }
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
            VerticalSpacerD600()
            PotPill(amount = potAmount, onClick = onPotClick)
        }
    }
}

@Composable
private fun BoardWell(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(Radii.R700.shape)
            .border(
                width = 1.5.dp,
                color = AppTheme.colors.poker.cardSlotOutline.color,
                shape = Radii.R700.shape,
            ),
    )
}

@Composable
private fun BoardCard(card: Card, revealDelayMs: Int, size: PlayingCardSize) {
    // Compose previews don't drive animations to completion — without this the
    // card would render mid-flight (translated up, half-flipped). In preview,
    // jump straight to the settled face-up state. "Instant" game speed does the
    // same so the board fills with no cosmetic delay.
    val inPreview = LocalInspectionMode.current
    val tempo = LocalTableTempo.current
    val skip = inPreview || tempo.isInstant
    key(card) {
        var arrived by remember { mutableStateOf(skip) }
        var revealed by remember { mutableStateOf(skip) }
        var settled by remember { mutableStateOf(skip) }
        // Key the effect on [skip] rather than gating its existence with an
        // `if (!skip)`. The persisted Table-speed setting resolves a beat after
        // the screen mounts, so [skip] can flip Normal -> Instant while a card
        // is still mid-reveal. Gating the effect would tear it down on that flip
        // and freeze the card half-revealed; re-keying instead restarts it and
        // the skip branch snaps the card straight to settled.
        LaunchedEffect(skip) {
            if (skip) {
                arrived = true
                revealed = true
                settled = true
                return@LaunchedEffect
            }
            delay(tempo.delay(revealDelayMs))
            arrived = true
            delay(tempo.delay(340))
            revealed = true
            delay(tempo.delay(420))
            settled = true
        }
        if (settled) {
            // Animation finished — render plain, no graphicsLayer.
            PlayingCard(card = card, size = size)
        } else {
            val flightDp = -120f
            val flightPx = with(LocalDensity.current) { flightDp.dp.toPx() }
            val translationY by animateFloatAsState(
                targetValue = if (arrived) 0f else flightPx,
                animationSpec = tween(tempo.duration(340), easing = FastOutSlowInEasing),
                label = "board-fly",
            )
            val rotation by animateFloatAsState(
                targetValue = if (revealed) 180f else 0f,
                animationSpec = tween(tempo.duration(380)),
                label = "board-flip",
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
private fun PotPill(amount: Long, onClick: () -> Unit) {
    val pillBackground = LocalFeltAccentSurface.current ?: AppTheme.colors.surfaceRaised.color
    // Publish the pill's bounds so the pot-ship coins know where to launch from.
    val anchors = LocalTableRewardAnchors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .then(
                if (anchors != null) {
                    Modifier.onGloballyPositioned { anchors.potBounds = it.boundsInRoot() }
                } else {
                    Modifier
                },
            )
            .clip(Radii.Round.shape)
            .background(pillBackground)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(Radii.Round.shape)
                .background(AppTheme.colors.poker.chipGold.color),
        )
        Text(
            text = stringResource(Res.string.room_board_pot_pill_label),
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.contentSecondary,
        )
        Text(
            text = formatCompactChips(amount),
            typography = AppTheme.typography.Heading.H600,
            color = AppTheme.colors.content,
        )
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
    // Showdown: the winner's hand (a royal flush in hearts) lights the three heart
    // board cards; the off-suit 3 and 7 dim. The pot pill sits at the awarded total.
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
