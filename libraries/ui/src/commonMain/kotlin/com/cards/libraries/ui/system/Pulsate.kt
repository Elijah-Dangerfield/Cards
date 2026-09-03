package com.dangerfield.cards.libraries.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import com.dangerfield.cards.libraries.ui.components.rememberLoopingFloat
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer


fun Modifier.pulsate(scale: Float = 1.2f) = composed {

    // State + a graphicsLayer read, not `by` + Modifier.scale. This is an
    // *infinite* animation on a shared modifier, so unwrapping it here would
    // recompose every caller's subtree at 60fps for as long as it is on screen.
    val scaleAnim = rememberLoopingFloat(
        initialValue = 1f,
        targetValue = scale,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulsate",
    )

    this.graphicsLayer {
        val s = scaleAnim.value
        scaleX = s
        scaleY = s
    }
}