package com.dangerfield.cards.libraries.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale


fun Modifier.pulsate(scale: Float = 1.2f) = composed {

    val infiniteTransition = rememberInfiniteTransition(label = "")

    val scaleAnim by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = scale,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ), label = "pluse animation"
    )

    this.scale(scaleAnim)
}