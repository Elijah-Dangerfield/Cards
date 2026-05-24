package com.dangerfield.cards.features.room.impl

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Renders a single 1.5s "emoji blast" over the play surface. When [blast]
 * becomes non-null the overlay enters, animates, and reports completion
 * via [onAnimationComplete] with the original blast's `emittedAtEpochMs`.
 * Caller clears VM state on that callback; the overlay then leaves the
 * tree.
 *
 * Per product-spec.md §5.5: ~1500ms total. Pop in fast, drift upward
 * while scaling + fading — a clear "throw" silhouette without
 * obstructing the table for more than a beat.
 */
@Composable
internal fun EmojiBlastOverlay(
    blast: EmojiBlast?,
    onAnimationComplete: (emittedAtEpochMs: Long) -> Unit,
    modifier: Modifier = Modifier,
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
        coroutineScope {
            launch {
                driftAnim.animateTo(
                    targetValue = DRIFT_FRACTION,
                    animationSpec = tween(BLAST_TOTAL_MS - POP_IN_MS, easing = LinearOutSlowInEasing),
                )
            }
            launch {
                alphaAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(BLAST_TOTAL_MS - POP_IN_MS, easing = FastOutSlowInEasing),
                )
            }
        }
        onAnimationComplete(current.emittedAtEpochMs)
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = current.emoji,
            typography = AppTheme.typography.Display.D1500,
            color = AppTheme.colors.text,
            modifier = Modifier.graphicsLayer {
                alpha = alphaAnim.value
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
                translationY = -size.height * driftAnim.value
            },
        )
    }
}

private const val BLAST_TOTAL_MS = 1500
private const val POP_IN_MS = 200
private const val SCALE_START = 0.4f
private const val SCALE_PEAK = 1.0f
/** Drift up by ~30% of the emoji's own height by the end of the animation. */
private const val DRIFT_FRACTION = 0.30f
