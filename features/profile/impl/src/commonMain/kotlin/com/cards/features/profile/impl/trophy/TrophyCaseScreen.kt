package com.dangerfield.cards.features.profile.impl.trophy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Trophy Case — display-only surface for unlock-only cosmetics earned
 * via progression (legendary achievements, league finishes, RFT,
 * achievement chains). Peer to [com.dangerfield.cards.features.profile.impl.items.MyItemsScreen]
 * but disjoint: My Items renders purchased products with equip toggles;
 * Trophy Case renders earned items with no equip path (ownership itself
 * is the prestige signal).
 *
 * V1 status: empty state only. The server-side `unlock_only` filter
 * landed 2026-05-21 and `ProductCatalogSource.readById` bypasses it for
 * known ids, but the inventory-grant path that writes unlock-only
 * products into a user's inventory isn't wired yet — and no V1
 * unlock-only rows exist in the catalog either. Once both arrive,
 * this screen grows the populated state: owned items at top, locked
 * silhouettes for unearned ones (same pattern as the achievement grid).
 *
 * Pure stateless content composable — no ViewModel until there's data
 * to observe, per the "extract content-only when no state exists yet"
 * convention.
 */
@Composable
fun TrophyCaseScreen(
    onBack: () -> Unit,
) {
    Screen(
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = AppTheme.colors.background.color,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Dimension.D800),
        ) {
            Spacer(modifier = Modifier.height(Dimension.D200))
            IconButton(
                icon = Icons.ArrowBack("Back"),
                onClick = onBack,
                iconColor = AppTheme.colors.onSurfacePrimary,
            )
            Spacer(modifier = Modifier.height(Dimension.D700))

            Text(
                text = "Trophy case",
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.onSurfacePrimary,
            )
            Spacer(modifier = Modifier.height(Dimension.D300))
            Text(
                text = "Prestige cosmetics earned through progression. Display-only — these aren't in the shop.",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
            )
            Spacer(modifier = Modifier.height(Dimension.D900))

            EmptyState()
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimension.D800),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "🏆",
                typography = AppTheme.typography.Display.D900,
            )
            Spacer(modifier = Modifier.height(Dimension.D500))
            Text(
                text = "No trophies yet",
                typography = AppTheme.typography.Heading.H600,
                color = AppTheme.colors.onSurfacePrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(Dimension.D300))
            Text(
                text = "Earn legendary achievements or place in seasonal leagues to unlock prestige cosmetics. They'll show up here — never in the shop.",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview
@Composable
private fun TrophyCaseScreenPreview_Empty() {
    PreviewContent {
        TrophyCaseScreen(onBack = {})
    }
}
