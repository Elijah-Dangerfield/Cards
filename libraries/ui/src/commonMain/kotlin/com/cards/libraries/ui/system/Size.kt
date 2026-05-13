package com.dangerfield.cards.libraries.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

fun Size.round(): IntSize = IntSize(width.roundToInt(), height.roundToInt())
