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
import com.dangerfield.cards.system.typography.TypographyResource
import androidx.compose.ui.tooling.preview.Preview

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
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        ChipCoin(size = coinSize)
        Spacer(Modifier.width(12.dp))
        AnimatedCountUpText(
            amount = amount,
            typography = AppTheme.typography.Display.D1100,
            color = color,
        )
    }
}

/**
 * The bare odometer count-up — a thousands-separated number tweening from 0
 * to [amount] on first composition. Powers [AnimatedChipReveal]'s number,
 * and stands alone wherever a celebratory reveal wants the count-up without
 * the gold coin (e.g. a big "10,000 chips" headline that draws its own coin).
 */
@Composable
fun AnimatedCountUpText(
    amount: Long,
    typography: TypographyResource,
    color: ColorResource,
    modifier: Modifier = Modifier,
    durationMillis: Int = 1_100,
) {
    val animated = remember { Animatable(initialValue = 0f) }
    var displayed by remember { mutableStateOf(0L) }
    LaunchedEffect(amount) {
        animated.animateTo(
            targetValue = amount.toFloat(),
            animationSpec = tween(
                durationMillis = durationMillis,
                easing = FastOutSlowInEasing,
            ),
        ) {
            displayed = this.value.toLong()
        }
        // Pin the final value exactly — the tween's last frame can land a
        // hair short of the target due to float→long truncation.
        displayed = amount
    }
    Text(
        text = formatWithThousands(displayed),
        typography = typography,
        color = color,
        modifier = modifier,
    )
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
