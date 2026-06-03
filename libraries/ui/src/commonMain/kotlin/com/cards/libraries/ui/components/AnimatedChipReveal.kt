package com.dangerfield.cards.libraries.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Gold coin + odometer-style tween from 0 to [amount] on first
 * composition — a celebratory "you just got these chips" reveal.
 *
 * Bespoke (not [AnimatedNumberText]) on purpose: that primitive
 * suppresses animation during its mount-settle window to avoid "0 → real"
 * flashes on tab switches, whereas here the 0 → real count-up *is* the
 * point. Shared by the Home welcome dialog and the onboarding
 * starter-grant page so both reveal the same way.
 */
@Composable
fun AnimatedChipReveal(
    amount: Long,
    color: ColorResource,
    modifier: Modifier = Modifier,
    coinSize: Dp = 40.dp,
) {
    val animated = remember { Animatable(initialValue = 0f) }
    var displayed by remember { mutableStateOf(0L) }
    LaunchedEffect(amount) {
        animated.animateTo(
            targetValue = amount.toFloat(),
            animationSpec = tween(
                durationMillis = 1_100,
                easing = FastOutSlowInEasing,
            ),
        ) {
            displayed = this.value.toLong()
        }
        // Pin the final value exactly — the tween's last frame can land a
        // hair short of the target due to float→long truncation.
        displayed = amount
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        ChipCoin(
            size = coinSize,
            textTypography = AppTheme.typography.Heading.H700,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = formatWithThousands(displayed),
            typography = AppTheme.typography.Display.D1100,
            color = color,
        )
    }
}

private fun formatWithThousands(value: Long): String {
    val s = value.toString()
    val sb = StringBuilder()
    val len = s.length
    for (i in 0 until len) {
        if (i > 0 && (len - i) % 3 == 0) sb.append(',')
        sb.append(s[i])
    }
    return sb.toString()
}

@Preview
@Composable
private fun AnimatedChipRevealPreview() {
    PreviewContent {
        AnimatedChipReveal(amount = 10_000L, color = AppTheme.colors.poker.chipGold)
    }
}
