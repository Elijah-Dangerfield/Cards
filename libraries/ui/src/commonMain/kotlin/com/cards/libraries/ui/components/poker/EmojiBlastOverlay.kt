package com.dangerfield.cards.libraries.ui.components.poker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.EmojiBlast
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD300
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Renders a single "emoji blast" over the whole screen — pop in, hold,
 * then drift up and fade. When [blast] becomes non-null the overlay
 * enters, animates, and reports completion via [onAnimationComplete]
 * with the original blast's `emittedAtEpochMs`. Caller clears its state
 * on that callback; the overlay then leaves the tree.
 *
 * Total duration is ~2.8s — long enough for the surface to register the
 * reaction without obstructing it for more than a beat. A hold-at-peak
 * between the pop and the drift gives the eye time to read the emoji
 * before it starts moving.
 *
 * The optional emitter avatar (small circle below the emoji) signals
 * *who* blasted. The poker table passes the human seat; the Profile
 * "Try it out" preview passes the viewer's own avatar.
 *
 * Shared (in `:libraries:ui`) so the in-game emote tray and the Profile
 * cosmetics "Try it out" affordance render the identical animation — a
 * single tight contract for the blast visual.
 */
@Composable
fun EmojiBlastOverlay(
    blast: EmojiBlast?,
    onAnimationComplete: (emittedAtEpochMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    emitterName: String? = null,
    emitterEmoji: String? = null,
    emitterColorHex: String? = null,
) {
    val current = blast ?: return

    val scaleAnim = remember(current.emittedAtEpochMs) { Animatable(SCALE_START) }
    val alphaAnim = remember(current.emittedAtEpochMs) { Animatable(0f) }
    val driftAnim = remember(current.emittedAtEpochMs) { Animatable(0f) }

    LaunchedEffect(current.emittedAtEpochMs) {
        coroutineScope {
            launch {
                alphaAnim.animateTo(1f, tween(POP_IN_MS, easing = FastOutSlowInEasing))
            }
            launch {
                scaleAnim.animateTo(SCALE_PEAK, tween(POP_IN_MS, easing = FastOutSlowInEasing))
            }
        }
        delay(HOLD_MS.toLong())
        coroutineScope {
            launch {
                driftAnim.animateTo(
                    targetValue = DRIFT_FRACTION,
                    animationSpec = tween(DRIFT_MS, easing = LinearOutSlowInEasing),
                )
            }
            launch {
                alphaAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(DRIFT_MS, easing = FastOutSlowInEasing),
                )
            }
        }
        onAnimationComplete(current.emittedAtEpochMs)
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer {
                alpha = alphaAnim.value
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
                translationY = -size.height * driftAnim.value
            },
        ) {
            Text(
                text = current.emoji,
                typography = AppTheme.typography.Display.D1500,
                color = AppTheme.colors.content,
            )
            if (emitterName != null) {
                VerticalSpacerD300()
                AvatarCircle(
                    name = emitterName,
                    emoji = emitterEmoji,
                    backgroundColorHex = emitterColorHex,
                    size = 32.dp,
                )
            }
        }
    }
}

private const val POP_IN_MS = 220
/** Hold the peak-size emoji for this long before starting the drift+fade. */
private const val HOLD_MS = 1100
private const val DRIFT_MS = 1500
private const val SCALE_START = 0.4f
private const val SCALE_PEAK = 1.0f
/** Drift up by ~50% of the column's height by the end of the animation. */
private const val DRIFT_FRACTION = 0.50f
