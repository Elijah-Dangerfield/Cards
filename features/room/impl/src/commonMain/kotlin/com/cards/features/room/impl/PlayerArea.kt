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
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.formatCompactChips
import com.dangerfield.cards.libraries.ui.components.poker.BlindMarker
import com.dangerfield.cards.libraries.ui.components.poker.ChipPill
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardBack
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSlot
import com.dangerfield.cards.libraries.ui.components.poker.WinnerGlow
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import kotlinx.coroutines.delay

@Composable
internal fun PlayerArea(table: TableUiState.Active) {
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
    // Lock the row's content height to the hole-card height. If we derived it
    // from intrinsic sizing the info tile's content would change with `isActing`
    // (the last-action text shows/hides) and the row would shift shape every
    // time the turn flips. Locking matches the hole cards exactly and keeps the
    // info tile a consistent rectangle.
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
    modifier: Modifier = Modifier,
) {
    // The outer PlayerArea now carries the active-turn band and the winner
    // highlight, so this tile stays neutral — just the player's avatar and chip
    // info. Keeps the visual hierarchy clean (one gold accent, not two nested ones).
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AppTheme.colors.surfacePrimary.color)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = handLabel ?: "",
            typography = AppTheme.typography.Body.B500.Bold,
            color = AppTheme.colors.text,
        )
        Spacer(modifier = Modifier.height(if (handLabel != null) 6.dp else 0.dp))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(60.dp)) {
            if (isWinner) WinnerGlow(modifier = Modifier.size(56.dp))
            AvatarCircle(name = "You", size = 44.dp)
            BlindMarker(
                isDealer = seat.isDealer,
                isSmallBlind = seat.isSmallBlind,
                isBigBlind = seat.isBigBlind,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-2).dp, y = (-2).dp),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = formatCompactChips(seat.stack),
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (seat.contributedThisStreet > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            ChipPill(amount = seat.contributedThisStreet)
        }
        if (seat.lastAction != null && !seat.isActing) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = seat.lastAction.shortLabel(),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
            )
        }
    }
}
