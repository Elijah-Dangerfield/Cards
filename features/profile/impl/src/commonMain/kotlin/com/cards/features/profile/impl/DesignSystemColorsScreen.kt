package com.dangerfield.cards.features.profile.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.catalog.ColorCatalogBody
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension

/**
 * In-app design-system color catalog — the same swatches as the IDE `@Preview`s
 * ([com.dangerfield.cards.libraries.ui.catalog.ColorCatalogBody]), rendered on a
 * real device so colors can be checked in context. Reached from the QA menu.
 */
@Composable
fun DesignSystemColorsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Screen(
        modifier = modifier,
        topBar = {
            TopBar(
                title = "Colors",
                onNavigateBack = onBack,
                scrollState = scrollState,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .screenContentPadding(paddingValues = padding),
            verticalArrangement = Arrangement.spacedBy(Dimension.D900),
        ) {
            Text(
                text = "Design-system color tokens. Reach for a role (AppTheme.colors.xyz), never a raw color.",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
            )
            ColorCatalogBody()
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
