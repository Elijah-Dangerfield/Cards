package com.dangerfield.cards.libraries.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.translate
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline

fun Outline.translate(dx: Float = 0f, dy: Float = 0f): Outline = translate(Offset(dx, dy))

fun Outline.translate(offset: Offset): Outline = when (this) {
    is Outline.Generic -> Outline.Generic(Path().apply { addPath(path, offset) })
    is Outline.Rectangle -> Outline.Rectangle(rect.translate(offset))
    is Outline.Rounded -> Outline.Rounded(roundRect.translate(offset))
}

fun Outline.size(): Size = when (this) {
    is Outline.Generic -> path.getBounds().size
    is Outline.Rectangle -> rect.size
    is Outline.Rounded -> Size(roundRect.width, roundRect.height)
}

operator fun Outline.plus(other: Outline): Outline = Outline.Generic(
    Path().apply {
        addOutline(this@plus)
        addOutline(other)
    }
)
