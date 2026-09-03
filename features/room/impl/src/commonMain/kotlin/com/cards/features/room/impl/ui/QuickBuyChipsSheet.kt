package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_quick_buy_balance
import cards.libraries.resources.generated.resources.room_quick_buy_confirm_cancel
import cards.libraries.resources.generated.resources.room_quick_buy_confirm_charged_via
import cards.libraries.resources.generated.resources.room_quick_buy_confirm_cta
import cards.libraries.resources.generated.resources.room_quick_buy_pack_chips
import cards.libraries.resources.generated.resources.room_quick_buy_store_unavailable
import cards.libraries.resources.generated.resources.room_quick_buy_subtitle
import cards.libraries.resources.generated.resources.room_quick_buy_title
import cards.libraries.resources.generated.resources.shop_purchase_store_app_store
import cards.libraries.resources.generated.resources.shop_purchase_store_google_play
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Platform
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.ui.Elevation
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.CircularProgressIndicator
import com.dangerfield.cards.libraries.ui.components.Surface
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.dialog.AccessoryShape
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.asDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.products.StoreSku
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD100
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD500
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * In-game quick-buy chip-pack sheet — the non-invasive replacement for sending
 * a busted MP player to the Shop tab. Lists the catalog's [Product.ChipPack]s;
 * selecting one opens a lightweight confirm (price + which store charges you)
 * before the purchase fires, matching the storefront's `PurchaseConfirmSheet`
 * so the quick-buy path never skips the confirm the shop path shows — this is
 * one tap from a real charge at the just-busted moment.
 *
 * Confirming drives the same [com.dangerfield.cards.libraries.billing.PurchaseChipPackUseCase]
 * the shop uses, so the player tops up their wallet without ever leaving the
 * table (the bust dialog stays mounted underneath).
 *
 * State-driven overlay (like the screen's other sheets), not a nav route — the
 * VM owns `quickBuyOpen` and flips it false once a purchase settles, at which
 * point the screen drops this from composition. The list→confirm step is local
 * UI state: it resets for free when the sheet leaves composition.
 */
@Composable
internal fun QuickBuyChipsSheet(
    packs: List<Product.ChipPack>,
    chipBalance: Long?,
    purchaseInFlight: Boolean,
    onSelectPack: (Product.ChipPack) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedPack by remember { mutableStateOf<Product.ChipPack?>(null) }

    BottomSheet(
        onDismissRequest = onDismiss,
        backgroundColor = AppTheme.colors.surface,
        dragHandle = topAccessoryEmoji(emoji = "💰", style = AccessoryShape.Squircle).asDragHandle(),
        // While a purchase round-trips, don't let a swipe / scrim / back yank the
        // sheet out from under the spinner.
        sheetGesturesEnabled = !purchaseInFlight,
        shouldDismissOnBackPress = !purchaseInFlight,
        shouldDismissOnClickOutside = !purchaseInFlight,
    ) {
        when (val pack = selectedPack) {
            null -> QuickBuyPackList(
                packs = packs,
                chipBalance = chipBalance,
                onSelectPack = { selectedPack = it },
            )
            else -> QuickBuyConfirm(
                pack = pack,
                purchaseInFlight = purchaseInFlight,
                onConfirm = { onSelectPack(pack) },
                onBack = { selectedPack = null },
            )
        }
    }
}

@Composable
private fun QuickBuyPackList(
    packs: List<Product.ChipPack>,
    chipBalance: Long?,
    onSelectPack: (Product.ChipPack) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.room_quick_buy_title),
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.content,
        )
        VerticalSpacerD100()
        Text(
            text = stringResource(Res.string.room_quick_buy_subtitle),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
        )
        if (chipBalance != null) {
            VerticalSpacerD100()
            Text(
                text = stringResource(Res.string.room_quick_buy_balance, formatThousands(chipBalance)),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
            )
        }
        VerticalSpacerD500()
        if (packs.isEmpty()) {
            Text(
                text = stringResource(Res.string.room_quick_buy_store_unavailable),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
        } else {
            packs.forEachIndexed { index, pack ->
                if (index > 0) VerticalSpacerD300()
                ChipPackRow(
                    pack = pack,
                    enabled = true,
                    onClick = { onSelectPack(pack) },
                )
            }
        }
    }
}

@Composable
private fun QuickBuyConfirm(
    pack: Product.ChipPack,
    purchaseInFlight: Boolean,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = pack.title,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.content,
        )
        VerticalSpacerD100()
        Text(
            text = stringResource(Res.string.room_quick_buy_pack_chips, formatThousands(pack.grantsChips)),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
        )
        VerticalSpacerD500()
        Text(
            text = pack.store.fallbackPriceDisplay,
            typography = AppTheme.typography.Heading.H900,
            color = AppTheme.colors.content,
        )
        VerticalSpacerD200()
        Text(
            text = stringResource(Res.string.room_quick_buy_confirm_charged_via, platformStoreName()),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
        )
        VerticalSpacerD500()
        Column(modifier = Modifier.fillMaxWidth()) {
            ButtonPrimary(
                onClick = onConfirm,
                enabled = !purchaseInFlight,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(Res.string.room_quick_buy_confirm_cta))
            }
            VerticalSpacerD300()
            ButtonSecondary(
                onClick = onBack,
                enabled = !purchaseInFlight,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(Res.string.room_quick_buy_confirm_cancel))
            }
        }
        if (purchaseInFlight) {
            VerticalSpacerD500()
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun platformStoreName(): String =
    when (BuildInfo.platform) {
        Platform.iOS -> stringResource(Res.string.shop_purchase_store_app_store)
        Platform.Android -> stringResource(Res.string.shop_purchase_store_google_play)
    }

@Composable
private fun ChipPackRow(
    pack: Product.ChipPack,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AppTheme.colors.surfaceRaised,
        contentColor = AppTheme.colors.content,
        radius = Radii.Card,
        elevation = Elevation.None,
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = Dimension.D700, vertical = Dimension.D600),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimension.D600),
        ) {
            Text(
                text = pack.iconEmoji,
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pack.title,
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.content,
                )
                Text(
                    text = stringResource(
                        Res.string.room_quick_buy_pack_chips,
                        formatThousands(pack.grantsChips),
                    ),
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.contentSecondary,
                )
            }
            Text(
                text = pack.store.fallbackPriceDisplay,
                typography = AppTheme.typography.Heading.H600,
                color = AppTheme.colors.accentPrimary,
            )
        }
    }
}

@Preview
@Composable
private fun QuickBuyChipsSheetPreview() {
    PreviewContent {
        QuickBuyChipsSheet(
            packs = listOf(
                Product.ChipPack(
                    id = "chip_pack_small",
                    title = "Pocket Stack",
                    subtitle = "5,000 chips",
                    iconEmoji = "🪙",
                    grantsChips = 5_000,
                    store = StoreSku("chips_small", "$0.99"),
                ),
                Product.ChipPack(
                    id = "chip_pack_medium",
                    title = "Tall Stack",
                    subtitle = "30,000 chips",
                    iconEmoji = "💰",
                    featured = true,
                    badge = "BEST VALUE",
                    grantsChips = 30_000,
                    store = StoreSku("chips_medium", "$4.99"),
                ),
            ),
            chipBalance = 1_200,
            purchaseInFlight = false,
            onSelectPack = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun QuickBuyChipsSheetPreview_Confirm() {
    PreviewContent {
        QuickBuyConfirm(
            pack = Product.ChipPack(
                id = "chip_pack_medium",
                title = "Tall Stack",
                subtitle = "30,000 chips",
                iconEmoji = "💰",
                featured = true,
                badge = "BEST VALUE",
                grantsChips = 30_000,
                store = StoreSku("chips_medium", "$4.99"),
            ),
            purchaseInFlight = false,
            onConfirm = {},
            onBack = {},
        )
    }
}
