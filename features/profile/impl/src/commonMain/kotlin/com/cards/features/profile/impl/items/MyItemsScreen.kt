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
import androidx.compose.runtime.Composable
import com.dangerfield.cards.libraries.cards.isPersonalCosmetic
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.profile_my_items_badge_earned
import cards.libraries.resources.generated.resources.profile_my_items_badge_unlocked
import cards.libraries.resources.generated.resources.profile_my_items_button_equip
import cards.libraries.resources.generated.resources.profile_my_items_button_unequip
import cards.libraries.resources.generated.resources.profile_my_items_empty_body
import cards.libraries.resources.generated.resources.profile_my_items_empty_title
import cards.libraries.resources.generated.resources.profile_my_items_personal_cosmetic_tag
import cards.libraries.resources.generated.resources.profile_my_items_subtitle_empty
import cards.libraries.resources.generated.resources.profile_my_items_subtitle_populated
import cards.libraries.resources.generated.resources.profile_my_items_title
import com.dangerfield.cards.libraries.cards.AcquisitionSource
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.poker.CosmeticPreview
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.stringResource

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
        topBar = {
            TopBar(
                title = stringResource(Res.string.profile_my_items_title),
                onNavigateBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .screenContentPadding(paddingValues = padding),
        ) {
            Spacer(modifier = Modifier.height(Dimension.D300))
            Text(
                text = if (state.ownedItems.isEmpty()) {
                    stringResource(Res.string.profile_my_items_subtitle_empty)
                } else {
                    stringResource(
                        Res.string.profile_my_items_subtitle_populated,
                        state.ownedItems.size,
                    )
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
        CosmeticPreview(
            productId = item.productId,
            emoji = item.iconEmoji,
            size = 48.dp,
        )
        Spacer(modifier = Modifier.size(Dimension.D500))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                typography = AppTheme.typography.Heading.H500,
                color = AppTheme.colors.onSurfacePrimary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.subtitle,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.onSurfaceSecondary,
                )
                if (item.isEquippable && item.acquisitionSource == AcquisitionSource.Earned) {
                    Spacer(modifier = Modifier.size(Dimension.D200))
                    EarnedTag()
                }
                if (isPersonalCosmetic(item.productId)) {
                    Spacer(modifier = Modifier.size(Dimension.D200))
                    PersonalCosmeticTag()
                }
            }
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
        if (item.isEquippable) {
            EquipToggleButton(isEquipped = item.isEquipped, onClick = onToggle)
        } else {
            OwnershipBadge(source = item.acquisitionSource)
        }
    }
}

@Composable
private fun OwnershipBadge(source: AcquisitionSource) {
    val label = when (source) {
        AcquisitionSource.Earned -> stringResource(Res.string.profile_my_items_badge_earned)
        AcquisitionSource.Purchased -> stringResource(Res.string.profile_my_items_badge_unlocked)
    }
    val textColor = when (source) {
        AcquisitionSource.Earned -> AppTheme.colors.accentSecondary
        AcquisitionSource.Purchased -> AppTheme.colors.onSurfaceSecondary
    }
    Box(
        modifier = Modifier
            .clip(Radii.R500.shape)
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = Dimension.D400, vertical = Dimension.D200),
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Label.L400,
            color = textColor,
        )
    }
}

@Composable
private fun EarnedTag() {
    Box(
        modifier = Modifier
            .clip(Radii.R400.shape)
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = Dimension.D300, vertical = Dimension.D100),
    ) {
        Text(
            text = stringResource(Res.string.profile_my_items_badge_earned),
            typography = AppTheme.typography.Label.L300,
            color = AppTheme.colors.accentSecondary,
        )
    }
}

/**
 * Mirrors the shop's per-tile "Only you" badge so the user sees the same
 * affordance pre- and post-purchase. Categorisation lives in
 * [isPersonalCosmetic].
 */
@Composable
private fun PersonalCosmeticTag() {
    Box(
        modifier = Modifier
            .clip(Radii.R400.shape)
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = Dimension.D300, vertical = Dimension.D100),
    ) {
        Text(
            text = stringResource(Res.string.profile_my_items_personal_cosmetic_tag),
            typography = AppTheme.typography.Label.L300,
            color = AppTheme.colors.onSurfaceSecondary,
        )
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
        Text(
            if (isEquipped) {
                stringResource(Res.string.profile_my_items_button_unequip)
            } else {
                stringResource(Res.string.profile_my_items_button_equip)
            }
        )
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
            text = stringResource(Res.string.profile_my_items_empty_title),
            typography = AppTheme.typography.Heading.H600,
            color = AppTheme.colors.onSurfacePrimary,
        )
        Spacer(modifier = Modifier.height(Dimension.D300))
        Text(
            text = stringResource(Res.string.profile_my_items_empty_body),
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
                    isEquippable = true,
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
                    isEquippable = true,
                ),
                onToggle = {},
            )
            OwnedItemRow(
                item = OwnedItem(
                    productId = "avatars_animals",
                    title = "Animals avatar pack",
                    subtitle = "Avatar pack",
                    description = "8 new avatars unlocked in Edit profile.",
                    iconEmoji = "🐶",
                    isEquipped = false,
                    isEquippable = false,
                ),
                onToggle = {},
            )
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun OwnedItemRowPreview_Earned() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimension.D400),
            modifier = Modifier.padding(Dimension.D500),
        ) {
            OwnedItemRow(
                item = OwnedItem(
                    productId = "ck.legendary",
                    title = "Legendary felt",
                    subtitle = "Felt",
                    description = "Earned for a legendary-tier achievement.",
                    iconEmoji = "🏆",
                    isEquipped = false,
                    isEquippable = true,
                    acquisitionSource = AcquisitionSource.Earned,
                ),
                onToggle = {},
            )
            OwnedItemRow(
                item = OwnedItem(
                    productId = "emote_pack.legendary",
                    title = "Legendary emote pack",
                    subtitle = "Emote pack",
                    description = "Unlocked from an achievement chain.",
                    iconEmoji = "✨",
                    isEquipped = false,
                    isEquippable = false,
                    acquisitionSource = AcquisitionSource.Earned,
                ),
                onToggle = {},
            )
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun MyItemsScreenPreview_Populated() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        MyItemsScreen(
            state = previewPopulatedState(),
            onAction = {},
            onBack = {},
        )
    }
}

private fun previewPopulatedState(): MyItemsState {
    val felt = com.dangerfield.cards.libraries.products.Product.ChipOffer(
        id = "felt_midnight_blue",
        title = "Midnight Blue Felt",
        subtitle = "Table felt",
        iconEmoji = "🟦",
        costChips = 1500,
        grantsKey = "felt.midnight_blue",
        description = "Deep blue felt — easy on the eyes during long sessions.",
        isEquippable = true,
    )
    val cardBack = com.dangerfield.cards.libraries.products.Product.ChipOffer(
        id = "cardback_comeback_kid",
        title = "Comeback Kid Card Back",
        subtitle = "Card back",
        iconEmoji = "🃏",
        costChips = 0,
        grantsKey = "cardback.comeback_kid",
        description = "Earned for clawing back from short stack to full.",
        isEquippable = true,
    )
    val emotes = com.dangerfield.cards.libraries.products.Product.ChipOffer(
        id = "emotes_drama",
        title = "Drama Emote Pack",
        subtitle = "Emotes · 4 reactions",
        iconEmoji = "💃",
        costChips = 3500,
        grantsKey = "emotes.drama",
        description = "Unlocks 💃 🧂 🎭 🤦 — big screen-filling reactions.",
        isEquippable = false,
    )
    val title = com.dangerfield.cards.libraries.products.Product.ChipOffer(
        id = "title_pot_magnet",
        title = "Pot Magnet",
        subtitle = "Title",
        iconEmoji = "🧲",
        costChips = 0,
        grantsKey = "title.pot_magnet",
        description = "Unlocked by sitting at a pot 25× the big blind.",
        isEquippable = true,
    )
    return MyItemsState(
        inventory = listOf(
            com.dangerfield.cards.libraries.cards.InventoryItem(
                productId = felt.id,
                state = com.dangerfield.cards.libraries.cards.PurchaseState.Confirmed,
                purchasedAtEpochMs = 1_700_000_000_000,
                costChipsAtPurchase = felt.costChips,
            ),
            com.dangerfield.cards.libraries.cards.InventoryItem(
                productId = cardBack.id,
                state = com.dangerfield.cards.libraries.cards.PurchaseState.Confirmed,
                purchasedAtEpochMs = 1_700_000_100_000,
                costChipsAtPurchase = 0,
                acquisitionSource = AcquisitionSource.Earned,
            ),
            com.dangerfield.cards.libraries.cards.InventoryItem(
                productId = emotes.id,
                state = com.dangerfield.cards.libraries.cards.PurchaseState.Confirmed,
                purchasedAtEpochMs = 1_700_000_200_000,
                costChipsAtPurchase = emotes.costChips,
            ),
            com.dangerfield.cards.libraries.cards.InventoryItem(
                productId = title.id,
                state = com.dangerfield.cards.libraries.cards.PurchaseState.Confirmed,
                purchasedAtEpochMs = 1_700_000_300_000,
                costChipsAtPurchase = 0,
                acquisitionSource = AcquisitionSource.Earned,
            ),
        ),
        catalog = com.dangerfield.cards.libraries.products.ProductCatalog(
            chipOffers = listOf(felt, cardBack, emotes, title),
        ),
        equippedIds = setOf(felt.id, cardBack.id),
    )
}
