package com.dangerfield.cards.features.shop.impl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.StoreSku
import com.dangerfield.cards.libraries.ui.Elevation
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.ChipCoinAmount
import com.dangerfield.cards.libraries.ui.components.Surface
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BasicBottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetValue
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.EmojiHandleStyle
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.rememberBottomSheetState
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD100
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD400
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD700
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Bottom sheet that confirms a purchase before committing it. Two flavors,
 * picked by the [pending] variant:
 *  - [PendingPurchase.IapPack] → real-money pack via the platform store.
 *  - [PendingPurchase.ChipOffer] → chip-funded cosmetic / equippable.
 *
 * Drag-handle slot uses the DS [IconBubbleDragHandle] with a chip-themed
 * bubble — sets a recognizable "you're about to spend chips" feel before
 * the user reads a single word.
 *
 * Dismiss animation contract: cancel and confirm both route through
 * `state.dismiss()` so the slide-down animation plays before the terminal
 * action fires. Without this, flipping `pendingPurchase = null` from a
 * synchronous click handler yanks the sheet out of composition mid-animation
 * and the user sees a hard snap.
 *
 * The [pendingTerminalAction] holds "what to fire when the hide animation
 * completes":
 *  - Cancel sets it to [onDismiss]
 *  - Confirm sets it to [onConfirm]
 *  - Scrim tap / swipe / back press leave it null → fall back to [onDismiss]
 */
@Composable
internal fun PurchaseConfirmSheet(
    pending: PendingPurchase,
    chipBalance: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(BottomSheetValue.Expanded)
    var pendingTerminalAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Bubble icon = the product's own emoji from the server. The
    // server is authoritative for what a product looks like; there's no
    // client-side mapping. Purchase sheets use the squircle style so
    // the bubble doubles as an "app-icon-like" product callout.
    val productEmoji = when (pending) {
        is PendingPurchase.IapPack -> pending.product.iconEmoji
        is PendingPurchase.ChipOffer -> pending.product.iconEmoji
    }
    val handle: BottomSheetDragHandle = BottomSheetDragHandle.Emoji(
        emoji = productEmoji,
        style = EmojiHandleStyle.Squircle,
    )

    BasicBottomSheet(
        state = sheetState,
        onDismissRequest = {
            // Animation has completed by the time we land here. Fire whichever
            // terminal action was queued; default to onDismiss for gesture-
            // initiated dismissals where neither button was tapped.
            (pendingTerminalAction ?: onDismiss).invoke()
        },
        backgroundColor = AppTheme.colors.surfacePrimary,
        dragHandle = handle,
    ) {
        val animatedConfirm: () -> Unit = {
            pendingTerminalAction = onConfirm
            sheetState.dismiss()
        }
        val animatedCancel: () -> Unit = {
            pendingTerminalAction = onDismiss
            sheetState.dismiss()
        }
        when (pending) {
            is PendingPurchase.IapPack -> IapPackConfirmContent(
                pack = pending.product,
                onConfirm = animatedConfirm,
                onCancel = animatedCancel,
            )
            is PendingPurchase.ChipOffer -> ChipOfferConfirmContent(
                offer = pending.product,
                chipBalance = chipBalance,
                onConfirm = animatedConfirm,
                onCancel = animatedCancel,
            )
        }
    }
}

@Composable
private fun IapPackConfirmContent(
    pack: Product.ChipPack,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = pack.title,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.text,
        )
        VerticalSpacerD100()
        Text(
            text = pack.subtitle,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.textSecondary,
        )
        pack.badge?.let {
            VerticalSpacerD300()
            BadgePill(text = it, accent = ColorResource.Amber600)
        }
        VerticalSpacerD500()
        Text(
            text = pack.store.fallbackPriceDisplay,
            typography = AppTheme.typography.Heading.H900,
            color = AppTheme.colors.text,
        )
        VerticalSpacerD200()
        Text(
            text = "Charged via your ${platformStoreName()}.",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
        VerticalSpacerD500()
        SheetButtons(
            confirmLabel = "Buy now",
            onConfirm = onConfirm,
            onCancel = onCancel,
            confirmEnabled = true,
        )
    }
}

@Composable
private fun ChipOfferConfirmContent(
    offer: Product.ChipOffer,
    chipBalance: Long,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val canAfford = chipBalance >= offer.costChips
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = offer.title,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.text,
        )
        VerticalSpacerD100()
        Text(
            text = offer.subtitle,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.textSecondary,
        )
        offer.badge?.let {
            VerticalSpacerD300()
            BadgePill(text = it, accent = ColorResource.Red400)
        }
        // Description is the "what does this DO" sentence the user needs
        // before committing — "Victory Dance" / "Bluff Master" / "Neon
        // Table" don't communicate behavior from the name alone. Rendered
        // as a tinted card so it reads as a distinct block ("here's what
        // you get") rather than blending into the subtitle.
        offer.description?.let { description ->
            VerticalSpacerD400()
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = AppTheme.colors.surfaceSecondary,
                contentColor = AppTheme.colors.onSurfaceSecondary,
                radius = Radii.Card,
                elevation = Elevation.None,
                onClick = {},
                bounceScale = 1f,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = description,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.text,
                    textAlign = TextAlign.Center,
                )
            }
        }
        VerticalSpacerD500()
        // Headline price — big coin to match Heading.H900 weight.
        ChipCoinAmount(
            amount = offer.costChips,
            coinSize = 32.dp,
            typography = AppTheme.typography.Heading.H900,
            color = AppTheme.colors.text,
            gap = 10.dp,
        )
        VerticalSpacerD500()
        BalancePreview(
            currentBalance = chipBalance,
            cost = offer.costChips,
            canAfford = canAfford,
        )
        VerticalSpacerD700()
        SheetButtons(
            confirmLabel = if (canAfford) "Buy now" else "Not enough chips",
            onConfirm = onConfirm,
            onCancel = onCancel,
            confirmEnabled = canAfford,
        )
    }
}

@Composable
private fun BalancePreview(currentBalance: Long, cost: Long, canAfford: Boolean) {
    val newBalance = (currentBalance - cost).coerceAtLeast(0)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AppTheme.colors.surfaceSecondary,
        contentColor = AppTheme.colors.onSurfaceSecondary,
        radius = Radii.Card,
        elevation = Elevation.None,
        onClick = {},
        bounceScale = 1f,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column {
            BalanceRow(label = "Your balance", amount = currentBalance)
            VerticalSpacerD200()
            BalanceRow(
                label = "After purchase",
                amount = newBalance,
                amountColor = if (canAfford) AppTheme.colors.text else AppTheme.colors.danger,
            )
        }
    }
}

@Composable
private fun BalanceRow(
    label: String,
    amount: Long,
    amountColor: ColorResource = AppTheme.colors.text,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ChipCoinAmount(
            amount = amount,
            coinSize = 18.dp,
            typography = AppTheme.typography.Body.B500,
            color = amountColor,
            gap = 6.dp,
        )
    }
}

@Composable
private fun SheetButtons(
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmEnabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ButtonPrimary(
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = confirmLabel)
        }
        VerticalSpacerD300()
        ButtonSecondary(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Cancel")
        }
    }
}

@Composable
private fun platformStoreName(): String =
    when (com.dangerfield.cards.libraries.core.BuildInfo.platform) {
        com.dangerfield.cards.libraries.core.Platform.iOS -> "App Store"
        com.dangerfield.cards.libraries.core.Platform.Android -> "Google Play"
    }

// ---------------------------------------------------------------------------
// Previews — one per branch so we can eyeball the affordance for each path.
// ---------------------------------------------------------------------------

@Preview
@Composable
private fun PurchaseConfirmSheetPreview_IapPack() {
    PreviewContent {
        PurchaseConfirmSheet(
            pending = PendingPurchase.IapPack(
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
            chipBalance = 12_450,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun PurchaseConfirmSheetPreview_ChipOfferWithDescription() {
    PreviewContent {
        PurchaseConfirmSheet(
            pending = PendingPurchase.ChipOffer(
                Product.ChipOffer(
                    id = "emote_dance",
                    title = "Victory Dance",
                    subtitle = "Emote",
                    description = "Send a celebration dance to the table when you win a hand — fills everyone's screen for a beat. Equip from your items.",
                    iconEmoji = "💃",
                    costChips = 2_500,
                    grantsKey = "emote.dance",
                ),
            ),
            chipBalance = 12_450,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun PurchaseConfirmSheetPreview_ChipOfferCannotAfford() {
    PreviewContent {
        PurchaseConfirmSheet(
            pending = PendingPurchase.ChipOffer(
                Product.ChipOffer(
                    id = "title_high_roller",
                    title = "High Roller",
                    subtitle = "Player title",
                    description = "Rare title — shows under your name at the table for everyone to see. Equip from your items.",
                    iconEmoji = "🏆",
                    badge = "RARE",
                    costChips = 25_000,
                    grantsKey = "title.high_roller",
                ),
            ),
            chipBalance = 1_500,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun PurchaseConfirmSheetPreview_ChipOfferNoDescription() {
    PreviewContent {
        PurchaseConfirmSheet(
            pending = PendingPurchase.ChipOffer(
                Product.ChipOffer(
                    id = "cardback_marble",
                    title = "Marble",
                    subtitle = "Card back",
                    iconEmoji = "🂠",
                    costChips = 6_000,
                    grantsKey = "cardback.marble",
                ),
            ),
            chipBalance = 12_450,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

