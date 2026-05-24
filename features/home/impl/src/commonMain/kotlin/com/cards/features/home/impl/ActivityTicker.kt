package com.dangerfield.cards.features.home.impl

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.HorizontalSpacerD500
import kotlinx.coroutines.delay

/**
 * Rotating ticker per product-spec §2.4 — the "living ecosystem"
 * surface up top. Cycles through a small set of short signals on a
 * timer with a slide+fade transition.
 *
 * V1 uses canned copy. The ticker is shaped to take real signals
 * once they exist (promotions, big pots, season countdowns, friend
 * activity) — drop them into [signals] and the rendering stays the
 * same. Spec rule: never fake; for V1 we're surfacing structural
 * truths ("Season 1 of the Hall · 12 days left", "Bots warm and
 * waiting"), not invented player names.
 */
@Composable
internal fun ActivityTicker(
    signals: List<String>,
    modifier: Modifier = Modifier,
) {
    if (signals.isEmpty()) return
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(signals.size) {
        if (signals.size <= 1) return@LaunchedEffect
        while (true) {
            delay(ROTATION_MS)
            index = (index + 1) % signals.size
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = Dimension.D600, vertical = Dimension.D500),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = "✦",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.accentPrimary,
        )
        HorizontalSpacerD500()
        AnimatedContent(
            targetState = signals[index],
            transitionSpec = {
                (slideInVertically(animationSpec = tween(380)) { it / 2 } +
                    fadeIn(tween(380))) togetherWith
                    (slideOutVertically(animationSpec = tween(280)) { -it / 2 } +
                        fadeOut(tween(220)))
            },
            label = "activity-ticker",
        ) { message ->
            Text(
                text = message,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val ROTATION_MS = 4_200L
