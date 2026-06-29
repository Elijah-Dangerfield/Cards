package com.dangerfield.cards.libraries.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.typography.TypographyResource
import kotlin.time.TimeSource

/**
 * A text that animates between numeric values via a count-up tween — the
 * digits rapidly roll from the previous value to the new target and settle
 * with a soft ease. Use anywhere a number changes in response to user
 * action (XP, chip stack, pot) so the change reads as a celebratory moment
 * rather than a silent swap.
 *
 * The tween uses a fixed duration regardless of the delta so a small +20 XP
 * doesn't feel slow and a large +10,000 chip win doesn't feel chaotic. The
 * `FastOutSlowInEasing` curve makes the number fly out fast and settle slow,
 * which is the odometer feel.
 */
@Composable
fun AnimatedNumberText(
    value: Long,
    typography: TypographyResource,
    color: ColorResource,
    modifier: Modifier = Modifier,
    formatter: (Long) -> String = { formatThousands(it) },
    durationMillis: Int = 700,
    /**
     * Optional direction tints. When set, the digits flash [gainColor] while
     * counting up to a larger value and [lossColor] while counting down to a
     * smaller one, then settle back to [color] — e.g. a chip balance reading green
     * on a win and red on a loss when you return from a game. Null (default) keeps
     * the number a constant [color].
     */
    gainColor: ColorResource? = null,
    lossColor: ColorResource? = null,
) {
    val animated = remember { Animatable(initialValue = value.toFloat()) }
    var displayed by remember { mutableStateOf(value) }
    // The active direction tint while a change animates; null settles back to [color].
    var tint by remember { mutableStateOf<ColorResource?>(null) }
    // Warm-up window: snap silently for the first MOUNT_SETTLE_MS so the
    // common "ViewModel emits its default 0, then emits the real value" path
    // doesn't animate from 0 → real on every tab switch / screen entry. After
    // the window real changes animate normally.
    val mountMark = remember { TimeSource.Monotonic.markNow() }
    LaunchedEffect(value) {
        val warmUp = mountMark.elapsedNow().inWholeMilliseconds < MOUNT_SETTLE_MS
        if (warmUp) {
            animated.snapTo(value.toFloat())
            displayed = value
            tint = null
        } else {
            val previous = displayed
            tint = when {
                value > previous -> gainColor
                value < previous -> lossColor
                else -> null
            }
            animated.animateTo(
                targetValue = value.toFloat(),
                animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
            ) {
                displayed = this.value.toLong()
            }
            // Hold the tint a beat past the roll so the win/loss registers, then
            // settle back to the base color.
            kotlinx.coroutines.delay(TINT_HOLD_MS)
            tint = null
        }
    }
    Text(
        text = formatter(displayed),
        typography = typography,
        color = tint ?: color,
        modifier = modifier,
    )
}

private const val MOUNT_SETTLE_MS = 300L
private const val TINT_HOLD_MS = 600L
