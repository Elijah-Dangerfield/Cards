package com.dangerfield.cards.libraries.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.dangerfield.cards.system.Dimension

operator fun PaddingValues.plus(other: PaddingValues): PaddingValues = object : PaddingValues {

    override fun calculateBottomPadding(): Dp = this@plus.calculateBottomPadding() + other.calculateBottomPadding()

    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        this@plus.calculateLeftPadding(layoutDirection) + other.calculateLeftPadding(layoutDirection)

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        this@plus.calculateRightPadding(layoutDirection) + other.calculateRightPadding(layoutDirection)

    override fun calculateTopPadding(): Dp = this@plus.calculateTopPadding() + other.calculateTopPadding()
}


fun PaddingValues.horizontalScreenInsets() = this.plus(screenHorizontalInsets)

val screenHorizontalInsets = PaddingValues(horizontal = Dimension.D500)

@Composable
fun Modifier.screenContentPadding(
    paddingValues: PaddingValues,
    includeHorizontalInsets: Boolean = true,
    includeNavigationBars: Boolean = true,
    includeImePadding: Boolean = false,
): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    val scaffoldPadding = PaddingValues(
        top = paddingValues.calculateTopPadding(),
        bottom = paddingValues.calculateBottomPadding(),
        start = paddingValues.calculateStartPadding(layoutDirection),
        end = paddingValues.calculateEndPadding(layoutDirection)
    )

    var modifier = this.padding(scaffoldPadding)

    if (includeHorizontalInsets) {
        modifier = modifier.padding(screenHorizontalInsets)
    }

    if (includeNavigationBars) {
        modifier = modifier.navigationBarsPadding()
    }

    if (includeImePadding) {
        modifier = modifier.imePadding()
    }

    return modifier
}