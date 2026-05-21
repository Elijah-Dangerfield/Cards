package com.dangerfield.cards.features.profile.impl.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii

/**
 * Renders the player's inventory as a single scrollable list. Each row
 * shows the item's emoji + title + (optional) description + an
 * equip/unequip toggle on the right.
 *
 * The toggle is a small button that swaps label/style depending on the
 * current state — gives instant visual feedback when the optimistic
 * write lands, even before the server confirms. Pending sync state is
 * intentionally hidden from the user: ownership is treated as final the
 * moment the purchase row hits Room. If the server eventually rejects
 * (insufficient chips, etc.) we'd surface that as a separate toast and
 * undo locally, not as a "syncing…" affordance up front.
 *
 * Empty state: a friendly note + a back-to-shop affordance lives in the
 * empty container so the user has a path forward instead of a void.
 */
@Composable
fun MyItemsScreen(
    state: MyItemsState,
    onAction: (MyItemsAction) -> Unit,
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
                text = "My items",
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.onSurfacePrimary,
            )
            Spacer(modifier = Modifier.height(Dimension.D300))
            Text(
                text = if (state.ownedItems.isEmpty()) {
                    "Pick up card backs, felts, and emotes in the shop."
                } else {
                    "${state.ownedItems.size} unlocked — tap to equip."
                },
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
            )
            Spacer(modifier = Modifier.height(Dimension.D800))

            if (state.ownedItems.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(Dimension.D400),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.ownedItems, key = { it.productId }) { item ->
                        OwnedItemRow(
                            item = item,
                            onToggle = { onAction(MyItemsAction.ToggleEquipped(item.productId)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OwnedItemRow(item: OwnedItem, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surfacePrimary.color)
            .padding(horizontal = Dimension.D500, vertical = Dimension.D500),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.surfaceSecondary.color),
        ) {
            Text(
                text = item.iconEmoji,
                typography = AppTheme.typography.Heading.H600,
            )
        }
        Spacer(modifier = Modifier.size(Dimension.D500))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                typography = AppTheme.typography.Heading.H500,
                color = AppTheme.colors.onSurfacePrimary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.subtitle,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
            )
            item.description?.let { desc ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = desc,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.onSurfaceSecondary,
                )
            }
        }
        Spacer(modifier = Modifier.size(Dimension.D400))
        EquipToggleButton(isEquipped = item.isEquipped, onClick = onToggle)
    }
}

@Composable
private fun EquipToggleButton(isEquipped: Boolean, onClick: () -> Unit) {
    // Filled = primary CTA ("Equip" — the action we want them to take if
    // it isn't equipped). Outlined = "this is the current state, tap to
    // change" — softer visual treatment for the already-equipped case.
    Button(
        onClick = onClick,
        size = ButtonSize.Small,
        style = if (isEquipped) ButtonStyle.Outlined else ButtonStyle.Filled,
    ) {
        Text(if (isEquipped) "Unequip" else "Equip")
    }
}

@Composable
private fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimension.D900),
    ) {
        Text(
            text = "🎁",
            typography = AppTheme.typography.Display.D900,
        )
        Spacer(modifier = Modifier.height(Dimension.D500))
        Text(
            text = "No items yet",
            typography = AppTheme.typography.Heading.H600,
            color = AppTheme.colors.onSurfacePrimary,
        )
        Spacer(modifier = Modifier.height(Dimension.D300))
        Text(
            text = "Pick up card backs, felts, and emote packs from the shop. " +
                "Once you own them, they'll show up here.",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.onSurfaceSecondary,
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun MyItemsScreenPreview_Empty() {
    // The full screen, no items. Exercises the empty-state hero path.
    com.dangerfield.cards.libraries.ui.PreviewContent {
        MyItemsScreen(state = MyItemsState(), onAction = {}, onBack = {})
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun OwnedItemRowPreview_Equipped_AndUnequipped() {
    // Row-only previews because MyItemsState.ownedItems is a derived
    // join over inventory + catalog — we'd need to fake both to seed
    // the screen with items. Pinning the row visual is what matters.
    com.dangerfield.cards.libraries.ui.PreviewContent {
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimension.D400),
            modifier = Modifier.padding(Dimension.D500),
        ) {
            OwnedItemRow(
                item = OwnedItem(
                    productId = "ck.gold",
                    title = "Gold card backs",
                    subtitle = "Card back",
                    description = "Luxe deck back with subtle gradient.",
                    iconEmoji = "🃏",
                    isEquipped = true,
                ),
                onToggle = {},
            )
            OwnedItemRow(
                item = OwnedItem(
                    productId = "felt.marble",
                    title = "Marble felt",
                    subtitle = "Felt",
                    description = null,
                    iconEmoji = "🟢",
                    isEquipped = false,
                ),
                onToggle = {},
            )
        }
    }
}
