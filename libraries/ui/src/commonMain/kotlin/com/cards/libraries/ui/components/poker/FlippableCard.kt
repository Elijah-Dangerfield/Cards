package com.dangerfield.cards.libraries.ui.components.poker

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.system.thenIf
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * A real playing card you can turn over: the [style]'d [PlayingCardBack] on
 * the front, a face-up [PlayingCard] ([faceCard], default A♠) on the back. The
 * achievement equivalent is [com.dangerfield.cards.libraries.ui.components.achievement.AchievementMedal]'s
 * two-sided coin — this is the same flip, but for an actual card so a player
 * can inspect a card-back cosmetic in a detail sheet and "see both sides."
 *
 * Unlike the medal (which paints both faces in one Canvas and pre-mirrors its
 * reverse text), the two faces here are real composables, so the back is
 * counter-rotated 180° via its own [graphicsLayer] to cancel the container's
 * mirroring — no manual mirror math.
 *
 * [flipOnInit] plays a one-shot 360° turn on first composition (the
 * "tah-dah" when the sheet opens). [interactive] lets the user grab the card:
 * a horizontal drag maps straight to [rotationY] so it can be held at any
 * angle, a tap flips to the other face, and on release it settles to the
 * nearest face like a real card dropped on felt.
 */
@Composable
fun FlippableCard(
    style: CardBackStyle,
    size: PlayingCardSize,
    modifier: Modifier = Modifier,
    faceCard: Card = AceOfSpades,
    avatarOverlay: AvatarBackOverlay? = null,
    flipOnInit: Boolean = false,
    interactive: Boolean = false,
) {
    // Plain float source of truth for the Y rotation, driven synchronously by
    // the drag and by `animate(...)` for the init spin / tap-flip / settle. A
    // plain float (not an Animatable fed by per-event coroutines) can never
    // land on NaN, which a stray drag delta otherwise could — and `roundToInt`
    // on NaN crashes. Mirrors AchievementMedal.
    var rotation by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var animJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        if (flipOnInit) {
            animJob = scope.launch {
                animate(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
                ) { value, _ -> rotation = value }
            }
        }
    }

    // Derived so only a face-change recomposes the Box (swapping the child) —
    // the smooth turn lives in the graphicsLayer, which reads `rotation` in its
    // deferred draw lambda. Reading `rotation` here directly would recompose
    // every frame of the flip.
    val showingBack by remember {
        derivedStateOf {
            val angle = ((rotation % 360f) + 360f) % 360f
            angle > 90f && angle < 270f
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                rotationY = rotation
                // Lower cameraDistance = stronger perspective, so the flip
                // reads as a card turning through space rather than a flat
                // horizontal squash. Same value the medal uses.
                cameraDistance = 9f * density
            }
            .thenIf(interactive) {
                pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { animJob?.cancel() },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            if (dragAmount.isFinite()) rotation += dragAmount * DegreesPerPixel
                        },
                        onDragEnd = {
                            // Settle to the nearest full turn — push past
                            // halfway and it completes; let go early and it
                            // falls back, like turning a real card.
                            val rest = (rotation / 360f).roundToInt() * 360f
                            animJob = scope.launch {
                                animate(
                                    initialValue = rotation,
                                    targetValue = rest.toFloat(),
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessLow,
                                    ),
                                ) { value, _ -> rotation = value }
                            }
                        },
                    )
                }.pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            val target = (rotation / 180f).roundToInt() * 180f + 180f
                            animJob = scope.launch {
                                animate(
                                    initialValue = rotation,
                                    targetValue = target.toFloat(),
                                    animationSpec = tween(600, easing = FastOutSlowInEasing),
                                ) { value, _ -> rotation = value }
                            }
                        },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (showingBack) {
            // The container's rotationY mirrors children past 90°; counter-
            // rotate the face by 180° so it reads correctly.
            Box(modifier = Modifier.graphicsLayer { rotationY = 180f }) {
                PlayingCard(card = faceCard, size = size)
            }
        } else {
            PlayingCardBack(size = size, style = style, avatarOverlay = avatarOverlay)
        }
    }
}

/** The iconic card shown on the reverse when a back is flipped over. */
val AceOfSpades: Card = Card(Rank.Ace, Suit.Spades)

/** Drag sensitivity: degrees of Y-rotation per pixel of horizontal drag.
 *  Matches [com.dangerfield.cards.libraries.ui.components.achievement.AchievementMedal]
 *  so a card and a medal flip with the same feel. */
private const val DegreesPerPixel = 0.7f

@Preview
@Composable
private fun FlippableCardPreview() {
    PreviewContent {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            FlippableCard(style = CardBackStyle.Marble, size = PlayingCardSize.Hole)
            FlippableCard(style = CardBackStyle.Galaxy, size = PlayingCardSize.Hole, flipOnInit = true)
        }
    }
}
