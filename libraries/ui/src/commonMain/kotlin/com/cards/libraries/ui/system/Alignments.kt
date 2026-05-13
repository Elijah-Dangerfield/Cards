package com.dangerfield.cards.libraries.ui

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toOffset

fun Alignment.align(size: Size, space: Size, layoutDirection: LayoutDirection): Offset =
    align(size.round(), space.round(), layoutDirection).toOffset()
