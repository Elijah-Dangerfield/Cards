package com.dangerfield.cards.features.room.impl.ui

import com.dangerfield.cards.features.room.impl.HandResultView
import com.dangerfield.cards.features.room.impl.SeatView
import com.dangerfield.cards.features.room.impl.TableUiState

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.Composable
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_player_odds_dial_lose_label
import cards.libraries.resources.generated.resources.room_player_odds_dial_no_value
import cards.libraries.resources.generated.resources.room_player_odds_dial_tie_label
import cards.libraries.resources.generated.resources.room_player_odds_dial_value
import cards.libraries.resources.generated.resources.room_player_odds_dial_win_label
import cards.libraries.resources.generated.resources.room_player_odds_flip_a11y
import cards.libraries.resources.generated.resources.room_player_odds_heading
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.bots.EquityBreakdown
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.cutout
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.ChipCoinAmount
import com.dangerfield.cards.libraries.ui.components.formatCompactChips
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.IconSize
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.poker.AvatarBackOverlay
import com.dangerfield.cards.libraries.ui.components.poker.BlindMarker
import com.dangerfield.cards.libraries.ui.components.poker.LocalTableSurface
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardBack
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSlot
import com.dangerfield.cards.libraries.ui.components.poker.TurnCountdownRing
import com.dangerfield.cards.libraries.ui.components.poker.WinnerGlow
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD100
import com.dangerfield.cards.system.VerticalSpacerD300
import kotlin.math.abs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
internal fun PlayerArea(
    table: TableUiState.Active,
    humanStackOverride: Long? = null,
    humanWinOdds: EquityBreakdown? = null,
    silentSwipeFold: Boolean = false,
    winOddsFlipHintSeen: Boolean = false,
    onWinOddsFlipped: () -> Unit = {},
    onBlindClick: () -> Unit = {},
    onBetPillClick: (seatName: String, amount: Long) -> Unit = { _, _ -> },
    onStackClick: () -> Unit = {},
    onHandLabelClick: (label: String) -> Unit = {},
    onSwipeFold: () -> Unit = {},
    onSelfTap: () -> Unit = {},
    availableEmojis: List<String> = emptyList(),
    emojiCooldownEndsAtMs: Long = 0L,
    onBlastEmoji: ((String) -> Unit)? = null,
) {
    val human = table.seats.firstOrNull { it.isHuman } ?: return
    val folded = human.participation == HandParticipation.Folded
    val isWinner = table.handResult?.winners?.any { it.seatIndex == human.index } == true
    // Pulse the band around the whole player area when it's the human's turn.
    // This replaces the dropped "Your turn" text banner.
    val pulseAlpha = if (human.isActing) pulseAlpha(low = 0.30f, high = 0.85f) else 0f
    val borderColor = when {
        isWinner -> AppTheme.colors.poker.chipGold.color
        human.isActing -> AppTheme.colors.poker.seatActive.color.copy(alpha = pulseAlpha)
        else -> Color.Transparent
    }
    val borderWidth = if (isWinner || human.isActing) 2.dp else 0.dp
    val swipeFoldEnabled = table.isHumanTurn &&
        table.humanLegalActions != null &&
        human.participation != HandParticipation.Folded
    // Two commit paths so the gesture reads as intentional, not
    // accidental:
    //   • drag past [foldCommitPx] — a deliberate "lift the cards
    //     out of your hand" motion (~70% of the hole-card height)
    //   • release with upward velocity past [foldFlickVelocity] and
    //     at least [foldMinFlickDistancePx] of upward displacement —
    //     a quick flick gesture
    // Neither alone is enough; you need *one* of these on release.
    // Below either threshold, the cards spring back. Distance is the
    // visual feedback that "I'm trying to fold," velocity is the
    // expert-user shortcut.
    val foldCommitPx = with(LocalDensity.current) { 100.dp.toPx() }
    val foldMinFlickDistancePx = with(LocalDensity.current) { 30.dp.toPx() }
    val foldFlickVelocityPxPerSec = with(LocalDensity.current) { 1_200.dp.toPx() }
    // Distance the cards travel after the user commits the fold — past the
    // top of the screen so they read as tossed away. Matches the existing
    // hole-card deal-in flight distance for symmetry.
    val foldFlightPx = with(LocalDensity.current) { 400.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    val gestureScope = rememberCoroutineScope()
    val dragOffsetY = remember { Animatable(0f) }
    // Manual "hide my cards" flip — toggled by tapping the hole-card
    // area. Resets to face-up whenever the dealt cards change so each
    // new hand starts visible (you wouldn't carry "hidden" state from
    // a hand you already saw into a fresh deal). Reads must coexist
    // with the swipe-up-to-fold drag, so we use a tap detector below
    // that fires only on release without movement — a real drag past
    // touch slop cancels the tap automatically.
    var manuallyFacedown by remember(human.holeCards) { mutableStateOf(false) }
    // 0..1 progress used to drive the in-flight visual response.
    // Visual progress saturates at the commit threshold so the
    // tilt/fade lands at a clear "ready to fold" peak when the user
    // is at the commit line — and doesn't keep growing if they
    // overshoot.
    val dragProgress = (abs(dragOffsetY.value) / foldCommitPx).coerceIn(0f, 1f)
    // Reset the offset whenever the gate flips back open (e.g. new hand),
    // so we never start with a stale residual translation from a prior
    // commit. Using a Compose effect keyed on `swipeFoldEnabled` keeps the
    // reset off the gesture coroutine, which gets cancelled by the
    // pointerInput re-key on the same flip.
    LaunchedEffect(swipeFoldEnabled) {
        if (swipeFoldEnabled && dragOffsetY.value != 0f) {
            dragOffsetY.snapTo(0f)
        }
    }
    // A silent swipe-fold flings the cards to `-foldFlightPx` (off the top) and
    // leaves them there; the gate-keyed reset above only fires once it's the
    // human's turn again, so between the fold and the next turn the freshly
    // dealt cards render stuck up top in a "ghost" placement (GAME-10). Snap
    // back on every new deal — keyed on the hole cards, which change identity
    // each hand — so the next hand always starts at rest regardless of turn.
    LaunchedEffect(human.holeCards) {
        if (dragOffsetY.value != 0f) {
            dragOffsetY.snapTo(0f)
        }
    }
    // Fixed row height — children inside use `fillMaxHeight()`, so this MUST
    // be bounded. `heightIn(min)` would let `fillMaxHeight` expand to the
    // parent's full offered height and the row would eat the whole screen.
    // Matches hole-card height so the info tile and the hole cards visually
    // align — the tile's content is sized tight enough to fit in this height
    // without clipping (see PlayerInfoTile).
    Row(
        // No .clip here: the emote + win-odds badges straddle the info-tile's
        // corners (half cut-out into the felt), so the row must let them hang
        // past its bounds instead of cropping them. The active-turn border still
        // renders rounded without a clip.
        modifier = Modifier
            .fillMaxWidth()
            .border(borderWidth, borderColor, Radii.R800.shape)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .height(PlayingCardSize.Hole.height),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                // Natural width (wraps the overlapping card pair) — NOT weight(1f),
                // which crammed two Hole cards into half the row, overflowing the
                // rounded border and clipping one card so the two looked unequal.
                // The seat tile takes the remaining space via its own weight(1f).
                .fillMaxHeight()
                .alpha(if (folded) 0.35f else 1f)
                .pointerInput(swipeFoldEnabled, foldCommitPx, foldFlickVelocityPxPerSec) {
                    if (!swipeFoldEnabled) return@pointerInput
                    val velocityTracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = {
                            velocityTracker.resetTracking()
                            gestureScope.launch { dragOffsetY.snapTo(0f) }
                        },
                        onDragEnd = {
                            // Commit only on release. Two paths qualify:
                            //   1. Drag past the commit distance.
                            //   2. Release with an upward flick past the
                            //      velocity threshold, with at least a
                            //      minimum displacement so a tap-and-twitch
                            //      doesn't qualify as a flick.
                            val finalOffset = dragOffsetY.value
                            val velocityY = velocityTracker.calculateVelocity().y
                            val draggedFarEnough = finalOffset <= -foldCommitPx
                            val flicked = velocityY <= -foldFlickVelocityPxPerSec &&
                                finalOffset <= -foldMinFlickDistancePx
                            if (draggedFarEnough || flicked) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (silentSwipeFold) {
                                    // User has already acknowledged the
                                    // gesture — continue the upward motion
                                    // off-screen as the fold animation
                                    // instead of cutting to the folded
                                    // state. Tween duration shortens when
                                    // the user flicked fast so the toss
                                    // feels continuous with their release
                                    // velocity.
                                    val durationMs = if (flicked) 220 else 280
                                    gestureScope.launch {
                                        dragOffsetY.animateTo(
                                            targetValue = -foldFlightPx,
                                            animationSpec = tween(
                                                durationMillis = durationMs,
                                                easing = FastOutSlowInEasing,
                                            ),
                                        )
                                    }
                                } else {
                                    // First-time gesture: the confirm
                                    // dialog is the moment, not the
                                    // flight. Spring back so cancel leaves
                                    // the cards in place and confirm hands
                                    // off to the engine's folded-state
                                    // alpha treatment.
                                    gestureScope.launch {
                                        dragOffsetY.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMediumLow,
                                            ),
                                        )
                                    }
                                }
                                onSwipeFold()
                            } else {
                                gestureScope.launch {
                                    dragOffsetY.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            gestureScope.launch {
                                dragOffsetY.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(),
                                )
                            }
                        },
                        onVerticalDrag = { change, dy ->
                            // Only follow upward motion past the rest point —
                            // dragging downward is a no-op so the cards don't
                            // bob below their resting line.
                            val next = (dragOffsetY.value + dy).coerceAtMost(0f)
                            gestureScope.launch { dragOffsetY.snapTo(next) }
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                        },
                    )
                }
                // Tap-to-flip the hole cards face-down (and back). On
                // release only — `detectTapGestures` cancels its tap if
                // motion exceeds touch slop, so the swipe-up-to-fold
                // drag above wins whenever the user actually drags.
                // Always enabled (even when the fold drag is gated off
                // mid-bot-turn) because hiding your own cards is a
                // casual action with no game-state preconditions.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { manuallyFacedown = !manuallyFacedown },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                // Overlap as a fraction of the card's own width — scales with the
                // ratio-derived card size rather than a hardcoded dp.
                horizontalArrangement = Arrangement.spacedBy(-(PlayingCardSize.Hole.width * 0.27f)),
                modifier = Modifier.graphicsLayer {
                    translationY = dragOffsetY.value
                    // Light tilt + fade tied to drag progress so the cards
                    // physically respond to the toss. Capped at small
                    // values — we want a flick, not a tumble.
                    rotationZ = -6f * dragProgress
                    alpha = 1f - 0.25f * dragProgress
                },
            ) {
                val humanAvatarOverlay = AvatarBackOverlay(
                    emoji = human.emoji ?: AnonymousAvatarEmoji,
                    backgroundColorHex = human.avatarBackgroundColorHex,
                )
                HoleCardSlot(
                    card = human.holeCards.getOrNull(0),
                    dealDelayMs = 0,
                    size = PlayingCardSize.Hole,
                    avatarOverlay = humanAvatarOverlay,
                    manuallyFacedown = manuallyFacedown,
                )
                HoleCardSlot(
                    card = human.holeCards.getOrNull(1),
                    dealDelayMs = 150,
                    size = PlayingCardSize.Hole,
                    avatarOverlay = humanAvatarOverlay,
                    manuallyFacedown = manuallyFacedown,
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            FlippablePlayerInfoTile(
                seat = human,
                handLabel = table.humanHandLabel,
                isWinner = isWinner,
                stackOverride = humanStackOverride,
                // Acting human on a timer-enforced (MP) table gets the depleting
                // countdown ring around their avatar; null on solo tables (no
                // enforcement) suppresses it. Re-arms on the turn token.
                countdownSeconds = table.turnTimerSeconds?.takeIf { human.isActing },
                turnKey = table.handNumber to table.turnSequence,
                winOdds = humanWinOdds,
                winOddsFlipHintSeen = winOddsFlipHintSeen,
                onFirstFlip = onWinOddsFlipped,
                onBlindClick = onBlindClick,
                onBetPillClick = onBetPillClick,
                onStackClick = onStackClick,
                onHandLabelClick = onHandLabelClick,
                onSelfTap = onSelfTap,
                modifier = Modifier.fillMaxSize(),
            )
            // Emote blast lives here as a cutout badge in the seat's bottom-right
            // corner (moved off the top bar). Null when emotes aren't available
            // (e.g. the tutorial), which hides the affordance entirely.
            if (onBlastEmoji != null) {
                SeatEmoteBadge(
                    emojis = availableEmojis,
                    cooldownEndsAtEpochMs = emojiCooldownEndsAtMs,
                    onBlast = onBlastEmoji,
                    // Inset into the tile's bottom-right corner so it reads as a
                    // cutout of the card, not a chip hanging off the edge into
                    // the felt. Small outward nudge keeps the cutout ring clear
                    // of the tile border.
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 6.dp, y = 2.dp),
                )
            }
        }
    }
}

/**
 * Drives the active-turn pulse for the human's player-area border. The
 * opponent seats draw their own gold ring; we keep this helper local because
 * the border alpha is woven into a `Color.copy(alpha = ...)` derivation that
 * doesn't fit a shared component's API.
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
private fun HoleCardSlot(
    card: Card?,
    dealDelayMs: Int,
    size: PlayingCardSize,
    avatarOverlay: AvatarBackOverlay? = null,
    manuallyFacedown: Boolean = false,
) {
    if (card == null) {
        PlayingCardSlot(size = size)
        return
    }
    // Compose previews don't drive animations to completion, so jump straight
    // to the settled face-up state there.
    val skip = LocalInspectionMode.current
    key(card) {
        var arrived by remember { mutableStateOf(skip) }
        var revealed by remember { mutableStateOf(skip) }
        var settled by remember { mutableStateOf(skip) }
        LaunchedEffect(skip) {
            if (skip) {
                arrived = true
                revealed = true
                settled = true
                return@LaunchedEffect
            }
            delay(dealDelayMs.toLong())
            arrived = true
            delay(320)
            revealed = true
            delay(420)
            settled = true
        }
        if (settled) {
            // Manual flip wrapper — once the deal-in animation has
            // landed, the user can tap to flip the card face-down (and
            // back). Same rotateY pattern as the deal-in flip but with
            // a much larger [cameraDistance] so the perspective stays
            // shallow — the card keeps its width through the rotation
            // instead of pinching at 90°. Both branches render inside
            // an identical centered Box so the rectangle the flip
            // occupies never shifts shape between front and back.
            val flipRotation by animateFloatAsState(
                targetValue = if (manuallyFacedown) 180f else 0f,
                animationSpec = tween(380),
                label = "hole-manual-flip",
            )
            Box(
                modifier = Modifier
                    .size(width = size.width, height = size.height)
                    .graphicsLayer {
                        rotationY = flipRotation
                        cameraDistance = 48f * density
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (flipRotation <= 90f) {
                    PlayingCard(card = card, size = size)
                } else {
                    Box(modifier = Modifier.graphicsLayer { rotationY = 180f }) {
                        PlayingCardBack(size = size, avatarOverlay = avatarOverlay)
                    }
                }
            }
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
                    PlayingCardBack(size = size, avatarOverlay = avatarOverlay)
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
    stackOverride: Long?,
    countdownSeconds: Int?,
    turnKey: Any,
    onBlindClick: () -> Unit,
    onBetPillClick: (seatName: String, amount: Long) -> Unit,
    onStackClick: () -> Unit,
    onHandLabelClick: (label: String) -> Unit,
    onSelfTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val hasBlindRole = seat.isDealer || seat.isSmallBlind || seat.isBigBlind
    // The outer PlayerArea now carries the active-turn band and the winner
    // highlight, so this tile stays neutral — just the player's avatar and chip
    // info. Keeps the visual hierarchy clean (one gold accent, not two nested ones).
    //
    // Layout:
    //   • Top header row — hand label (most game-functional info gets prime
    //     real estate); spans full tile width, clickable for the explainer.
    //   • Bigger centered avatar (52dp in a 56dp ring) so the player's
    //     identity reads clearly; previous 40dp avatar was tiny because the
    //     column was carrying too many stacked lines.
    //   • Name on its own line. The equipped title is intentionally NOT
    //     rendered here — the seat has too little real estate; it surfaces
    //     only on the tapped Player Card (PlayerProfileSheet).
    //   • Stack + optional chip/action pill at the bottom.
    //
    // The seat-level badge ("Lvl 14") that opponents show in this stack is
    // intentionally absent here — the human's level already lives in the
    // TopBar, so duplicating it inside their own card just inflates density.
    //
    // Dimensions are tuned tight so the content fits inside the locked
    // hole-card row height (PlayingCardSize.Hole.height = 140.dp) without
    // clipping the bottom chip pill. Adjust together if the row height changes.
    Column(
        modifier = modifier
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surface.color)
            .border(
                width = 1.dp,
                color = AppTheme.colors.border.color,
                shape = Radii.R700.shape,
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (handLabel != null) {
            Text(
                text = handLabel,
                typography = AppTheme.typography.Body.B500.Bold,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onHandLabelClick(handLabel) },
            )
            VerticalSpacerD100()
        }
        // Publish the human avatar's bounds so the pot-ship coins fly here when
        // the local player wins (the opponent seats publish their own).
        val seatAvatarAnchors = LocalTableRewardAnchors.current
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .then(
                    if (seatAvatarAnchors != null && seat.isHuman) {
                        Modifier.onGloballyPositioned {
                            seatAvatarAnchors.seatAvatarBounds[seat.index] = it.boundsInRoot()
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (countdownSeconds != null) {
                TurnCountdownRing(
                    turnKey = turnKey,
                    durationSeconds = countdownSeconds,
                    modifier = Modifier.size(56.dp),
                )
            }
            if (isWinner) WinnerGlow(modifier = Modifier.size(56.dp))
            // The circular clip + tap target lives on the avatar itself, NOT
            // the outer box. Clipping the outer box to a circle cropped the
            // BlindMarker (anchored at its bottom-end corner) against the
            // circle's curve — the dealer "D" was getting a flat edge.
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .then(
                        if (seat.isHuman) {
                            // Tapping your own avatar opens your Player Card
                            // (the public identity others see). The blind
                            // explainer stays on the BlindMarker badge below.
                            Modifier.clip(CircleShape).clickable { onSelfTap() }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                AvatarCircle(
                    name = seat.displayName,
                    size = 52.dp,
                    emoji = seat.emoji ?: AnonymousAvatarEmoji,
                    backgroundColorHex = seat.avatarBackgroundColorHex,
                )
            }
            // Top-left corner badge (matches the opponent-seat convention), cut
            // out of the player tile's own surface — not the page background — so
            // the ring reads as the tile color.
            BlindMarker(
                isDealer = seat.isDealer,
                isSmallBlind = seat.isSmallBlind,
                isBigBlind = seat.isBigBlind,
                cutoutColor = AppTheme.colors.surface.color,
                onClick = if (hasBlindRole) onBlindClick else null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 2.dp, y = 2.dp),
            )
            // Bet / last-action badge, cut into the avatar's bottom edge exactly
            // like the opponent seats (shared [SeatActionChip]) — a check tick, a
            // neutral call pill, or a gold bet/raise/all-in pill. This used to stack
            // as a full pill *below* the chip count, which pushed the column past
            // the locked row height and clipped the amount the human bet
            // (CARDS-78 / CARDS-7A). As a corner badge it costs no column height and
            // matches how opponents read. Cut out of the tile surface — the human
            // avatar sits on the card, not the felt.
            seat.lastAction?.let { action ->
                SeatActionChip(
                    action = action,
                    cutoutColor = AppTheme.colors.surface.color,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 7.dp)
                        .clickable { onBetPillClick("You", seat.contributedThisStreet) },
                )
            }
        }
        // Clearance for the action badge that straddles the avatar's bottom edge
        // (matches the opponent seats' 8dp gap) so it never sits on the name.
        VerticalSpacerD300()
        // Name only — the equipped title surfaces on the Player Card, not in
        // the seat's cramped name area.
        Text(
            text = seat.displayName,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.content,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        VerticalSpacerD100()
        // Publish the human's stack bounds so the hand-end coin particle knows
        // where to land. Only the human tile feeds the overlay; opponents and
        // the no-holder previews leave it untouched.
        val rewardAnchors = LocalTableRewardAnchors.current
        ChipCoinAmount(
            amount = stackOverride ?: seat.stack,
            coinSize = 14.dp,
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.content,
            gap = 5.dp,
            formatter = ::formatCompactChips,
            animated = stackOverride != null,
            modifier = Modifier
                .then(
                    if (seat.isHuman && rewardAnchors != null) {
                        Modifier.onGloballyPositioned { rewardAnchors.chipStackBounds = it.boundsInRoot() }
                    } else {
                        Modifier
                    },
                )
                .clickable(onClick = onStackClick),
        )
        // The bet / last-action badge is no longer stacked here — it now cuts into
        // the avatar's bottom edge above (matching the opponent seats), so the
        // column stays inside the locked row height without clipping.
    }
}

/**
 * Wraps [PlayerInfoTile] in a horizontal 3D flip. The back face shows the
 * human's live Win % / Lose % when the win-odds tool is owned + a value
 * has resolved; otherwise the tile stays a static front face with no
 * flip affordance, no gesture, no animation overhead.
 *
 * Gestures (only when [winOdds] != null):
 *  - Tap on the small Refresh glyph (top-right corner).
 *  - Horizontal drag past ~40dp.
 * Nested clickables inside the front face (hand label, stack, blind
 * marker, pill) still receive their own clicks — only "dead space" taps
 * fall through to the wrapper's tap-to-flip below.
 */
@Composable
private fun FlippablePlayerInfoTile(
    seat: SeatView,
    handLabel: String?,
    isWinner: Boolean,
    stackOverride: Long?,
    countdownSeconds: Int?,
    turnKey: Any,
    winOdds: EquityBreakdown?,
    winOddsFlipHintSeen: Boolean,
    onFirstFlip: () -> Unit,
    onBlindClick: () -> Unit,
    onBetPillClick: (seatName: String, amount: Long) -> Unit,
    onStackClick: () -> Unit,
    onHandLabelClick: (label: String) -> Unit,
    onSelfTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val canFlip = winOdds != null
    var flipped by rememberSaveable { mutableStateOf(false) }
    // Force back to front when the tool flips off mid-session — otherwise
    // re-enabling later would land on a blank back face.
    LaunchedEffect(canFlip) {
        if (!canFlip) flipped = false
    }
    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(durationMillis = 520),
        label = "info-tile-flip",
    )

    // Discoverability wiggle — fires once per session when the user owns
    // the tool AND has never flipped the tile before (persisted via
    // AppCache.winOddsFlipHintSeen). Keyed on canFlip + the persisted
    // seen-flag only — NOT on `flipped` — so a user tap mid-wiggle
    // doesn't cancel the animation halfway through and leave the tile
    // sitting at a stuck intermediate angle. The NonCancellable finally
    // snaps hintRotation back to 0 in case canFlip flips off (tool
    // un-equipped) or the persisted flag arrives mid-wiggle.
    val hintRotation = remember { Animatable(0f) }
    LaunchedEffect(canFlip, winOddsFlipHintSeen) {
        if (canFlip && !winOddsFlipHintSeen) {
            try {
                hintRotation.animateTo(-22f, tween(220, easing = FastOutSlowInEasing))
                hintRotation.animateTo(22f, tween(360, easing = LinearEasing))
                hintRotation.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
            } finally {
                withContext(NonCancellable) { hintRotation.snapTo(0f) }
            }
        }
    }

    // Single helper so every flip path (icon tap, swipe, back-tap)
    // routes through the same first-flip persistence call. Cheap when
    // already-seen because the cache write is idempotent.
    val toggleFlipped: (Boolean) -> Unit = { target ->
        flipped = target
        if (!winOddsFlipHintSeen) onFirstFlip()
    }

    val swipeCommitPx = with(LocalDensity.current) { 40.dp.toPx() }
    Box(
        modifier = modifier
            .graphicsLayer {
                rotationY = rotation + hintRotation.value
                cameraDistance = 14f * density
            }
            .pointerInput(canFlip, swipeCommitPx) {
                if (!canFlip) return@pointerInput
                var dragTotal = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onDragEnd = {
                        if (abs(dragTotal) >= swipeCommitPx) toggleFlipped(!flipped)
                    },
                    onHorizontalDrag = { _, dx -> dragTotal += dx },
                )
            },
    ) {
        if (rotation <= 90f) {
            PlayerInfoTile(
                seat = seat,
                handLabel = handLabel,
                isWinner = isWinner,
                stackOverride = stackOverride,
                countdownSeconds = countdownSeconds,
                turnKey = turnKey,
                onBlindClick = onBlindClick,
                onBetPillClick = onBetPillClick,
                onStackClick = onStackClick,
                onHandLabelClick = onHandLabelClick,
                onSelfTap = onSelfTap,
                modifier = Modifier.fillMaxSize(),
            )
            if (canFlip) {
                FlipAffordance(
                    onClick = { toggleFlipped(true) },
                    // Inset into the tile's top-right corner, matching the emote
                    // badge's cutout treatment.
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-2).dp),
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
                PlayerInfoTileBack(
                    winOdds = winOdds,
                    onTapClose = { toggleFlipped(false) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Back face of the flippable [PlayerInfoTile] — shows Win % and Lose %
 * side-by-side. Tie % is collapsed into the Win/Lose split unless it's
 * non-trivial (≥2%), in which case it surfaces as a small footer.
 */
@Composable
private fun PlayerInfoTileBack(
    winOdds: EquityBreakdown?,
    onTapClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surface.color)
            .border(
                width = 1.dp,
                color = AppTheme.colors.border.color,
                shape = Radii.R700.shape,
            )
            .clickable(onClick = onTapClose)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.room_player_odds_heading),
            typography = AppTheme.typography.Body.B400.Bold,
            color = AppTheme.colors.contentSecondary,
            maxLines = 1,
        )
        VerticalSpacerD100()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OddsDial(
                label = Res.string.room_player_odds_dial_win_label,
                percent = winOdds?.winPct,
                tone = OddsDialTone.Win,
            )
            OddsDial(
                label = Res.string.room_player_odds_dial_lose_label,
                percent = winOdds?.losePct,
                tone = OddsDialTone.Lose,
            )
        }
        if (winOdds != null && winOdds.tiePct >= 2) {
            VerticalSpacerD100()
            Text(
                text = stringResource(Res.string.room_player_odds_dial_tie_label, winOdds.tiePct),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
                maxLines = 1,
            )
        }
    }
}

private enum class OddsDialTone { Win, Lose }

@Composable
private fun OddsDial(
    label: StringResource,
    percent: Int?,
    tone: OddsDialTone,
) {
    val accent = when (tone) {
        OddsDialTone.Win -> AppTheme.colors.success
        OddsDialTone.Lose -> AppTheme.colors.danger
    }
    val displayPct = percent ?: 0
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(AppTheme.colors.surfaceRaised.color)
                .border(
                    width = 2.dp,
                    color = accent.color,
                    shape = androidx.compose.foundation.shape.CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (percent == null) {
                    stringResource(Res.string.room_player_odds_dial_no_value)
                } else {
                    stringResource(Res.string.room_player_odds_dial_value, displayPct)
                },
                typography = AppTheme.typography.Body.B500.Bold,
                color = accent,
                maxLines = 1,
            )
        }
        VerticalSpacerD100()
        Text(
            text = stringResource(label),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
            maxLines = 1,
        )
    }
}

/**
 * Small Refresh glyph anchored to the top-right of the tile to advertise
 * the flip. Only rendered when the win-odds tool is owned — otherwise
 * there's nothing to flip TO and the affordance would mislead.
 */
@Composable
private fun FlipAffordance(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Cut out of the player-area card surface (same convention as the emote
    // badge) so the glyph reads as punched into the tile's corner rather than a
    // chip floating on top. The felt-toned ring separates the cutout from the
    // tile border.
    val cutoutColor = LocalTableSurface.current ?: AppTheme.colors.background.color
    Box(
        modifier = modifier
            .size(22.dp)
            .clickable(onClick = onClick)
            .cutout(
                ringColor = cutoutColor,
                fillColor = AppTheme.colors.surface.color,
                shape = androidx.compose.foundation.shape.CircleShape,
                ringWidth = 2.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon = Icons.Refresh(stringResource(Res.string.room_player_odds_flip_a11y)),
            size = IconSize.Smallest,
            color = AppTheme.colors.contentSecondary,
        )
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
    isBusted = false,
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
    turnTimerSeconds: Int? = null,
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
    turnTimerSeconds = turnTimerSeconds,
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
private fun PlayerAreaPreview_YourTurnCountdown() {
    // MP table (turnTimerSeconds set) on the human's turn — the depleting
    // countdown ring wraps the human avatar so an auto-action never lands
    // without warning. Solo tables leave turnTimerSeconds null (no ring).
    PreviewContent {
        PlayerArea(
            table = previewTable(
                seat = previewHumanSeat(isActing = true),
                turnTimerSeconds = 30,
            ),
        )
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
