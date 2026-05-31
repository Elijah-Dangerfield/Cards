package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.dangerfield.cards.libraries.ui.system.LocalColors
import androidx.compose.material3.pulltorefresh.PullToRefreshBox as MaterialPullToRefreshBox

/**
 * Design-system pull-to-refresh container. Thin wrapper over Material 3's
 * `PullToRefreshBox` that swaps the default indicator for one tinted with
 * the app's accent color + surface, so callsites don't bleed the global
 * red Material sentinel theme into the spinner.
 *
 * Mirrors the [CircularProgressIndicator] wrapper pattern: same Material
 * API surface, opinionated default for color, escape hatch via parameter
 * overrides for the rare one-off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    state: PullToRefreshState = rememberPullToRefreshState(),
    contentAlignment: Alignment = Alignment.TopStart,
    indicatorColor: Color = LocalColors.current.accentPrimary.color,
    indicatorContainerColor: Color = LocalColors.current.surface.color,
    indicator: @Composable BoxScope.() -> Unit = {
        PullToRefreshDefaults.Indicator(
            modifier = Modifier.align(Alignment.TopCenter),
            isRefreshing = isRefreshing,
            state = state,
            color = indicatorColor,
            containerColor = indicatorContainerColor,
        )
    },
    content: @Composable BoxScope.() -> Unit,
) {
    MaterialPullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        contentAlignment = contentAlignment,
        indicator = indicator,
        content = content,
    )
}
