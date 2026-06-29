package com.dangerfield.cards.features.room.impl.ui

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.features.room.impl.SeatView
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.cutout
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.ChipCoinAmount
import com.dangerfield.cards.libraries.ui.components.formatCompactChips
import com.dangerfield.cards.libraries.ui.components.poker.BlindMarker
import com.dangerfield.cards.libraries.ui.components.poker.LocalTableSurface
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.poker.TurnCountdownRing
import com.dangerfield.cards.libraries.ui.components.poker.WinnerGlow
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD100
import org.jetbrains.compose.ui.tooling.preview.Preview

/** Felt-colored band between the avatar and the gold ring (the "cutout"). */
private val SeatCutoutRing: Dp = 3.dp

/** Gold turn / aggressor ring thickness, just outside the cutout. */
private val SeatGoldRing: Dp = 2.5.dp

/**
 * One opponent at the table — the "player circle". Three non-overlapping zones,
 * each with its own lifespan, so they never collide:
 *
 *  - **Position** (top-left, whole hand): white `D` / `SB` / `BB` badge.
 *  - **Action** (bottom-center, this round): green check / neutral call pill /
 *    gold bet-raise-allin pill. Gold == chips going in.
 *  - **Turn** (ring): the gold countdown (timed human), a pulsing gold "to act"
 *    ring (untimed bot/solo), or the solid gold aggressor ring.
 *  - **Out** recolors the whole avatar: folded greys + mucks; busted greys + ✕.
 *
 * The avatar is ringed by a felt-colored cutout so it reads as punched out of the
 * table — [LocalTableSurface] feeds the exact backdrop color.
 *
 * See [OpponentSeatStatesPreview] for every position × state laid out in a row.
 */
@Composable
internal fun OpponentSeat(
    seat: SeatView,
    isWinner: Boolean,
    winAmount: Long,
    handComplete: Boolean,
    avatarSize: Dp,
    turnTimerSeconds: Int?,
    turnKey: Any,
    onBlindClick: () -> Unit = {},
    onLastActionClick: (seatName: String, action: PlayerAction) -> Unit = { _, _ -> },
    onAvatarTap: () -> Unit = {},
) {
    val folded = seat.participation == HandParticipation.Folded
    val busted = seat.isBusted
    val outOfHand = folded || busted
    val hasBlindRole = seat.isDealer || seat.isSmallBlind || seat.isBigBlind
    val cutoutColor = seatCutoutColor()
    val cutoutDiscSize = avatarSize + SeatCutoutRing * 2
    val ringSize = cutoutDiscSize + SeatGoldRing * 2

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

            // The avatar, ringed by a felt-colored cutout so it reads as punched
            // out of the table. The avatar (only) greys when out of the hand.
            Box(
                modifier = Modifier
                    .size(cutoutDiscSize)
                    .clip(CircleShape)
                    .background(cutoutColor),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = dimMod) {
                    AvatarCircle(
                        name = seat.displayName,
                        size = avatarSize,
                        emoji = seat.emoji,
                        backgroundColorHex = seat.avatarBackgroundColorHex,
                    )
                }
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

            // Position badge — top-left, persists the whole hand. Cut out of the
            // felt the seat sits on.
            BlindMarker(
                isDealer = seat.isDealer,
                isSmallBlind = seat.isSmallBlind,
                isBigBlind = seat.isBigBlind,
                cutoutColor = cutoutColor,
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

/** The exact backdrop color the seat sits on, for the cutout ring + badge cuts. */
@Composable
private fun seatCutoutColor(): Color = LocalTableSurface.current ?: AppTheme.colors.background.color

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
            initialValue = 0.5f,
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
            width = SeatGoldRing,
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
            .clickable(onClick = onClick)
            .cutout(
                ringColor = seatCutoutColor(),
                fillColor = AppTheme.colors.success.color,
                shape = CircleShape,
                ringWidth = 2.dp,
            ),
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
            .clickable(onClick = onClick)
            .cutout(
                ringColor = seatCutoutColor(),
                fillColor = if (gold) AppTheme.colors.poker.chipGold.color else AppTheme.colors.surfaceHigh.color,
                shape = Radii.Round.shape,
                ringWidth = 1.5.dp,
            )
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
            .cutout(
                ringColor = seatCutoutColor(),
                fillColor = AppTheme.colors.surfaceHigh.color,
                shape = RoundedCornerShape(2.dp),
                ringWidth = 1.dp,
            ),
    )
}

// ---------------------------------------------------------------------------
// Preview — the player circle as a standalone design unit: every position
// (D / SB / BB) across states (acting / checked / raised / folded / busted),
// laid out in a row on a felt so the cutout ring reads.
// ---------------------------------------------------------------------------

@Preview
@Composable
private fun OpponentSeatStatesPreview() {
    // A non-default felt so the felt-colored cutout ring is visible around each
    // avatar (on the default felt it matches the app background and disappears).
    val felt = Color(0xFF14322B)
    PreviewContent {
        CompositionLocalProvider(LocalTableSurface provides felt) {
            Row(
                modifier = Modifier
                    .background(felt)
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                PreviewSeat(
                    PreviewSamples.botSeat(index = 1, name = "Acting", isDealer = true, isActing = true)
                        .copy(isBot = false, avatarBackgroundColorHex = "#5b82c4"),
                    turnTimerSeconds = 30,
                )
                PreviewSeat(
                    PreviewSamples.botSeat(
                        index = 2,
                        name = "Checked",
                        isSmallBlind = true,
                        lastAction = PlayerAction.Check,
                    ).copy(avatarBackgroundColorHex = "#3f9485"),
                )
                PreviewSeat(
                    PreviewSamples.botSeat(
                        index = 3,
                        name = "Raised",
                        isBigBlind = true,
                        contributed = 50,
                        lastAction = PlayerAction.Raise(totalStreetContribution = 50, raiseAmount = 30),
                    ).copy(avatarBackgroundColorHex = "#876fbf"),
                )
                PreviewSeat(
                    PreviewSamples.botSeat(
                        index = 4,
                        name = "Folded",
                        participation = HandParticipation.Folded,
                        lastAction = PlayerAction.Fold,
                    ).copy(avatarBackgroundColorHex = "#c46f86"),
                )
                PreviewSeat(
                    PreviewSamples.botSeat(index = 5, name = "Busted", stack = 0)
                        .copy(avatarBackgroundColorHex = "#b8443f"),
                )
            }
        }
    }
}

@Composable
private fun PreviewSeat(seat: SeatView, turnTimerSeconds: Int? = null) {
    OpponentSeat(
        seat = seat,
        isWinner = false,
        winAmount = 0L,
        handComplete = false,
        avatarSize = 56.dp,
        turnTimerSeconds = turnTimerSeconds,
        turnKey = 0,
    )
}
