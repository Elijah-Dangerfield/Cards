package com.dangerfield.cards.libraries.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

fun Modifier.bounceClick(
    enabled: Boolean = true,
    mutableInteractionSource: MutableInteractionSource? = null,
    indication: Indication? = null,
    scaleDown: Float = 0.90f,
    onClick: () -> Unit = {}
) = composed {

    val interactionSource = mutableInteractionSource ?: remember {  MutableInteractionSource() }

    val animatable = remember { Animatable(1f) }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> animatable.animateTo(scaleDown)
                is PressInteraction.Release -> animatable.animateTo(1f)
                is PressInteraction.Cancel -> animatable.animateTo(1f)
            }
        }
    }

    this
        .graphicsLayer {
            val scale = animatable.value
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = indication,
            onClick = onClick
        )
}