package com.dangerfield.cards.libraries.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import kotlin.math.min

fun Modifier.fadingEdge(
    scrollState: ScrollState,
    startHeight: Dp = Dimension.D500,
    ): Modifier = this.composed {

    val color = AppTheme.colors.background.color

    Modifier
        // adding layer fixes issue with blending gradient and content
        .graphicsLayer { alpha = 0.99F }
        .drawWithContent {
            drawContent()

            val bottomColors = listOf(color, Color.Transparent)
            val bottomEndY = size.height - scrollState.maxValue + scrollState.value
            val bottomGradientHeight =
                min(startHeight.toPx(), scrollState.maxValue.toFloat() - scrollState.value)
            if (bottomGradientHeight != 0f) drawRect(
                brush = Brush.verticalGradient(
                    colors = bottomColors,
                    startY = bottomEndY - bottomGradientHeight,
                    endY = bottomEndY
                ),
                blendMode = BlendMode.DstIn
            )
        }
}

fun Modifier.fadingEdge(
    listState: LazyListState,
): Modifier = this.composed {

    val color = AppTheme.colors.background.color

    Modifier
        // Adding layer fixes issue with blending gradient and content
        .graphicsLayer { alpha = 0.99F }
        .drawWithContent {
            drawContent()

            val defaultFadeHeight = Dimension.D1500.toPx()
            val itemCount = listState.layoutInfo.totalItemsCount
            val bottomColors = listOf(color, Color.Transparent)

            if (listState.canScrollForward) {

                val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.last()
                val lastItemIndex = lastVisibleItem.index
                val isLastItemBeingShown = lastItemIndex == itemCount - 1

                val fadeHeightPx = if (isLastItemBeingShown) {
                    val visibleHeight = listState.layoutInfo.viewportEndOffset - lastVisibleItem.offset
                    val itemHeight = lastVisibleItem.size
                    val visibleFraction = visibleHeight.toFloat() / itemHeight.toFloat()
                    // approaches 0 as visibility approaches 1
                    val heightMultiplier = (1 - visibleFraction).coerceAtLeast(0f).coerceAtMost(1f)
                    defaultFadeHeight * heightMultiplier
                } else  {
                    defaultFadeHeight
                }

                drawRect(
                    brush = Brush.verticalGradient(
                        colors = bottomColors,
                        startY = size.height - fadeHeightPx,
                        endY = size.height
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
        }
}

fun Modifier.horizontalFadingEdge(
    listState: LazyListState,
    fadeWidth: Dp = Dimension.D1100,
): Modifier = this.composed {

    val color = AppTheme.colors.background.color

    Modifier
        .graphicsLayer { alpha = 0.99F }
        .drawWithContent {
            drawContent()

            val fadeWidthPx = fadeWidth.toPx()

            if (listState.canScrollBackward) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(color, Color.Transparent),
                        startX = 0f,
                        endX = fadeWidthPx,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }

            if (listState.canScrollForward) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, color),
                        startX = size.width - fadeWidthPx,
                        endX = size.width,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}
