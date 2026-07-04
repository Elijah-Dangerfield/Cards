package com.dangerfield.cards.libraries.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD500
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Dot indicator for a [androidx.compose.foundation.pager.HorizontalPager] —
 * the current page reads as a stretched accent pill, the rest as muted dots.
 * The stretch and color animate as the page settles so a swipe always gets a
 * visible acknowledgement.
 */
@Composable
fun PagerIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
    activeColor: ColorResource = AppTheme.colors.accentPrimary,
    inactiveColor: ColorResource = AppTheme.colors.surfaceHigh,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimension.D200),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { page ->
            val active = page == currentPage
            val width by animateDpAsState(
                targetValue = if (active) ActiveDotWidth else DotSize,
                animationSpec = tween(220),
                label = "pager-dot-width",
            )
            val color by animateColorAsState(
                targetValue = if (active) activeColor.color else inactiveColor.color,
                animationSpec = tween(220),
                label = "pager-dot-color",
            )
            Box(
                modifier = Modifier
                    .height(DotSize)
                    .width(width)
                    .clip(Radii.Round.shape)
                    .background(color),
            )
        }
    }
}

private val DotSize = 6.dp
private val ActiveDotWidth = 16.dp

@Preview
@Composable
private fun PagerIndicatorPreview() {
    PreviewContent {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PagerIndicator(pageCount = 3, currentPage = 0)
            VerticalSpacerD500()
            PagerIndicator(pageCount = 5, currentPage = 2)
        }
    }
}
