package com.dangerfield.cards.libraries.ui.system.color

import androidx.compose.ui.graphics.Color
import platform.CoreGraphics.CGFloat
import platform.UIKit.UIColor

fun ColorResource.toUIColor(): UIColor = color.toUIColorInternal()

fun ColorResource?.toUIColorOrNull(): UIColor? {
    if (this == null || this == ColorResource.Unspecified) return null
    return this.toUIColor()
}

private fun Color.toUIColorInternal(): UIColor = UIColor(
    red = (red.toDouble()),
    green = (green.toDouble()),
    blue = (blue.toDouble()),
    alpha = (alpha.toDouble()),
)
