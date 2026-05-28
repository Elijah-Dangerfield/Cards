package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.dangerfield.cards.libraries.ui.screenHorizontalInsets
import com.dangerfield.cards.system.Dimension

/**
 * Horizontal `LazyRow` that breaks out of its parent's screen-edge padding
 * and re-applies the same padding as its own `contentPadding`. The visual
 * outcome: the first item still aligns with the surrounding page content,
 * but items can clip past the screen edge so the "scroll for more"
 * affordance is obvious.
 *
 * Use this anywhere a `LazyRow` lives inside a screen whose outer column
 * already applies the standard `screenContentPadding`. If a screen uses
 * non-standard horizontal page padding, fall back to a raw `LazyRow` with
 * explicit `contentPadding` — `EdgeToEdgeRow` assumes the standard inset.
 */
@Composable
fun EdgeToEdgeRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    horizontalSpacing: Dp = Dimension.D500,
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        modifier = modifier.escapeHorizontalScreenPadding(),
        state = state,
        contentPadding = screenHorizontalInsets,
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        content = content,
    )
}

/**
 * Expands the composable's horizontal measurement budget by the standard
 * screen inset on each side, then positions the result so the visual left
 * edge sits flush with the screen edge — escaping the screen's page padding
 * for callers like `EdgeToEdgeRow` that re-apply the same inset internally
 * as `contentPadding`.
 */
private fun Modifier.escapeHorizontalScreenPadding(): Modifier = layout { measurable, constraints ->
    val insetPx = screenHorizontalInsets.calculateLeftPadding(LayoutDirection.Ltr).roundToPx()
    val expanded = if (constraints.maxWidth == Int.MAX_VALUE) {
        constraints
    } else {
        constraints.copy(maxWidth = constraints.maxWidth + insetPx * 2)
    }
    val placeable = measurable.measure(expanded)
    layout(placeable.width, placeable.height) {
        placeable.placeRelative(-insetPx, 0)
    }
}
