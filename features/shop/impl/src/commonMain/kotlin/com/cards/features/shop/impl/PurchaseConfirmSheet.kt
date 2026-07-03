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
import com.dangerfield.cards.libraries.cards.CosmeticSlot
import com.dangerfield.cards.libraries.cards.cosmeticSlotFor
import com.dangerfield.cards.libraries.cards.formatThousands
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.shop_purchase_balance_after
import cards.libraries.resources.generated.resources.shop_purchase_balance_current
import cards.libraries.resources.generated.resources.shop_purchase_buy_now
import cards.libraries.resources.generated.resources.shop_purchase_cancel
import cards.libraries.resources.generated.resources.shop_purchase_charged_via
import cards.libraries.resources.generated.resources.shop_purchase_close
import cards.libraries.resources.generated.resources.shop_purchase_locked
import cards.libraries.resources.generated.resources.shop_purchase_locked_body
import cards.libraries.resources.generated.resources.shop_purchase_need_more_chips
import cards.libraries.resources.generated.resources.shop_purchase_owned_body_avatars
import cards.libraries.resources.generated.resources.shop_purchase_owned_body_default
import cards.libraries.resources.generated.resources.shop_purchase_owned_body_emotes
import cards.libraries.resources.generated.resources.shop_purchase_owned_body_equippable
import cards.libraries.resources.generated.resources.shop_purchase_owned_title
import cards.libraries.resources.generated.resources.shop_purchase_responsible_play
import cards.libraries.resources.generated.resources.shop_purchase_responsible_play_link
import cards.libraries.resources.generated.resources.shop_purchase_store_app_store
import cards.libraries.resources.generated.resources.shop_purchase_store_google_play
import cards.libraries.resources.generated.resources.shop_unlocks_at_level
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Platform
import com.dangerfield.cards.libraries.products.CatalogTimeAnchor
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.StoreSku
import com.dangerfield.cards.libraries.ui.Elevation
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.buildClickableText
import com.dangerfield.cards.libraries.ui.components.ChipCoinAmount
import com.dangerfield.cards.libraries.ui.components.Surface
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.dialog.AccessoryShape
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetState
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetValue
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.asDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.rememberBottomSheetState
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.poker.CosmeticPreview
import com.dangerfield.cards.libraries.ui.components.poker.FlippableCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.poker.cardBackForProductId
import com.dangerfield.cards.libraries.ui.components.text.ClickableText
import com.dangerfield.cards.libraries.ui.components.text.Text
import org.jetbrains.compose.resources.stringResource
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
 * Bottom sheet that confirms a purchase before committing it. Two
 * flavors, picked by the [product] subtype:
 *  - [Product.ChipPack] → real-money pack via the platform store.
 *  - [Product.ChipOffer] → chip-funded cosmetic / equippable.
 *
 * Drag-handle slot uses the DS [topAccessoryEmoji] with a chip-themed
 * bubble — sets a recognizable "you're about to spend chips" feel
 * before the user reads a single word.
 *
 * Mounted as the content of a `bottomSheet<ShopProductSheetRoute>`
 * destination, which is why [sheetState] is passed in rather than
 * `remember`'d here: it's owned by the destination so the route's
 * lifecycle drives the slide-in / slide-out animation.
 *
 * Dismiss animation contract: cancel and confirm both route through
 * `sheetState.dismiss()` so the slide-down animation plays before the
 * terminal action fires. Without this, popping the sheet route from a
 * synchronous click handler yanks the sheet out of composition mid-
 * animation and the user sees a hard snap.
 *
 * [pendingTerminalAction] holds "what to fire when the hide animation
 * completes":
 *  - Cancel sets it to [onDismiss]
 *  - Confirm sets it to [onConfirm]
 *  - Scrim tap / swipe / back press leave it null → fall back to
 *    [onDismiss]
 *
 * Both [onConfirm] and [onDismiss] are expected to pop the sheet
 * route; the caller wires that.
 */
@Composable
internal fun PurchaseConfirmSheet(
    sheetState: BottomSheetState,
    product: Product,
    mode: PurchaseSheetMode,
    chipBalance: Long,
    timeAnchor: CatalogTimeAnchor?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onOpenResponsiblePlay: () -> Unit = {},
) {
    var pendingTerminalAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Bubble icon = the product's own emoji from the server. The
    // server is authoritative for what a product looks like; there's no
    // client-side mapping. Purchase sheets use the squircle style so
    // the bubble doubles as an "app-icon-like" product callout, and the
    // bubble's fill mirrors the product's grid-card tile (gold tint, accent
    // tint, or the featured-pack gradient) so the tap-to-sheet transition
    // feels visually continuous.
    val handle: BottomSheetDragHandle = topAccessoryEmoji(
        emoji = product.iconEmoji,
        style = AccessoryShape.Squircle,
        surface = productBubbleSurface(product),
    ).asDragHandle()

    BottomSheet(
        state = sheetState,
        onDismissRequest = {
            // Animation has completed by the time we land here. Fire whichever
            // terminal action was queued; default to onDismiss for gesture-
            // initiated dismissals where neither button was tapped.
            (pendingTerminalAction ?: onDismiss).invoke()
        },
        backgroundColor = AppTheme.colors.surface,
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
        when (product) {
            is Product.ChipPack -> IapPackConfirmContent(
                pack = product,
                timeAnchor = timeAnchor,
                onConfirm = animatedConfirm,
                onCancel = animatedCancel,
                onExpired = animatedCancel,
                onOpenResponsiblePlay = onOpenResponsiblePlay,
            )
            is Product.ChipOffer -> ChipOfferConfirmContent(
                offer = product,
                mode = mode,
                chipBalance = chipBalance,
                timeAnchor = timeAnchor,
                onConfirm = animatedConfirm,
                onCancel = animatedCancel,
                onExpired = animatedCancel,
            )
        }
    }
}

@Composable
private fun IapPackConfirmContent(
    pack: Product.ChipPack,
    timeAnchor: CatalogTimeAnchor?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onExpired: () -> Unit,
    onOpenResponsiblePlay: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Sale-window countdown echo. Same composable + anchor math as
        // the grid card. Clock-spoof-resistant. Hits zero → onExpired.
        val saleEpochMs = pack.availableUntilEpochMs
        if (saleEpochMs != null && timeAnchor != null) {
            CountdownBadge(
                timeAnchor = timeAnchor,
                availableUntilEpochMs = saleEpochMs,
                onExpired = onExpired,
            )
            VerticalSpacerD300()
        }
        Text(
            text = pack.title,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.content,
        )
        VerticalSpacerD100()
        Text(
            text = pack.subtitle,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
        )
        pack.badge?.let {
            VerticalSpacerD300()
            BadgePill(text = it, accent = AppTheme.colors.accentPrimary)
        }
        VerticalSpacerD500()
        Text(
            text = pack.store.fallbackPriceDisplay,
            typography = AppTheme.typography.Heading.H900,
            color = AppTheme.colors.content,
        )
        VerticalSpacerD200()
        Text(
            text = stringResource(Res.string.shop_purchase_charged_via, platformStoreName()),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
        )
        VerticalSpacerD500()
        SheetButtons(
            confirmLabel = stringResource(Res.string.shop_purchase_buy_now),
            onConfirm = onConfirm,
            onCancel = onCancel,
            confirmEnabled = true,
        )
        // Quiet responsible-play line — only on the real-money pack path (chips
        // bought with chips don't spend cash). "Play responsibly" links to the
        // NCPG help page via the same ClickableText pattern as onboarding consent.
        VerticalSpacerD400()
        val responsiblePlayLink = stringResource(Res.string.shop_purchase_responsible_play_link)
        ClickableText(
            text = buildClickableText(stringResource(Res.string.shop_purchase_responsible_play)) {
                link(responsiblePlayLink) { onOpenResponsiblePlay() }
            },
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Chip-offer purchase sheet body. The sheet ALWAYS opens — even for
 * owned / locked / unaffordable items — so users can read the
 * description and learn "what is this?" before they qualify to buy.
 * The CTA + auxiliary copy varies per [mode].
 *
 *  - [PurchaseSheetMode.Available]   → headline price + BalancePreview +
 *    "Buy now" primary button.
 *  - [PurchaseSheetMode.Insufficient] → price + BalancePreview with the
 *    after-purchase row in danger color + disabled "Need X more chips"
 *    primary button.
 *  - [PurchaseSheetMode.Locked]      → "Unlocks at Level N" prompt
 *    inline (no chip cost shown) + disabled primary button.
 *  - [PurchaseSheetMode.Owned]       → "You own this" prompt + close-
 *    only buttons. The "manage in Your Items" hint is in the body copy.
 */
@Composable
private fun ChipOfferConfirmContent(
    offer: Product.ChipOffer,
    mode: PurchaseSheetMode,
    chipBalance: Long,
    timeAnchor: CatalogTimeAnchor?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onExpired: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Sale-window countdown echo, when present. Same primitive +
        // anchor as the grid card, so the countdown the user saw on the
        // card stays synced inside the sheet.
        val saleEpochMs = offer.availableUntilEpochMs
        if (saleEpochMs != null && timeAnchor != null) {
            CountdownBadge(
                timeAnchor = timeAnchor,
                availableUntilEpochMs = saleEpochMs,
                onExpired = onExpired,
            )
            VerticalSpacerD300()
        }
        Text(
            text = offer.title,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.content,
        )
        VerticalSpacerD100()
        Text(
            text = offer.subtitle,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
        )
        offer.badge?.let {
            VerticalSpacerD300()
            BadgePill(text = it, accent = AppTheme.colors.danger)
        }
        // Hero preview of the cosmetic itself — felts paint as a tinted
        // swatch, card backs render the real PlayingCardBack. The user
        // tapped a 64dp tile to get here; this larger surface answers
        // "yes, this is what you're buying." Skipped for non-cosmetic
        // offers (titles, emote/avatar packs) whose visual is the emoji
        // already shown in the drag-handle bubble.
        if (hasCosmeticPreview(offer.id)) {
            VerticalSpacerD400()
            // Card backs get the real flip-card (auto-flips on open, draggable),
            // same hero as the profile's cosmetic sheet; felts stay a swatch.
            if (cosmeticSlotFor(offer.id) == CosmeticSlot.CardBack) {
                FlippableCard(
                    style = cardBackForProductId(offer.id),
                    size = PlayingCardSize.Hole,
                    flipOnInit = true,
                    interactive = true,
                )
            } else {
                CosmeticPreview(
                    productId = offer.id,
                    emoji = offer.iconEmoji,
                    size = 120.dp,
                )
            }
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
                color = AppTheme.colors.surfaceRaised,
                contentColor = AppTheme.colors.contentSecondary,
                radius = Radii.Card,
                elevation = Elevation.None,
                onClick = {},
                bounceScale = 1f,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = description,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.content,
                    textAlign = TextAlign.Center,
                )
            }
        }
        VerticalSpacerD500()
        // Mode-specific commerce area: headline price + BalancePreview
        // for buyable modes; status prompt for locked/owned.
        when (mode) {
            is PurchaseSheetMode.Available, is PurchaseSheetMode.Insufficient -> {
                ChipCoinAmount(
                    amount = offer.costChips,
                    coinSize = 32.dp,
                    typography = AppTheme.typography.Heading.H900,
                    color = AppTheme.colors.content,
                    gap = 10.dp,
                )
                VerticalSpacerD500()
                BalancePreview(
                    currentBalance = chipBalance,
                    cost = offer.costChips,
                    canAfford = mode is PurchaseSheetMode.Available,
                )
            }
            is PurchaseSheetMode.Locked -> StatusPrompt(
                emoji = "🔒",
                title = stringResource(Res.string.shop_unlocks_at_level, mode.requiredLevel),
                body = stringResource(
                    Res.string.shop_purchase_locked_body,
                    mode.requiredLevel,
                    formatThousands(offer.costChips),
                ),
            )
            is PurchaseSheetMode.Owned -> StatusPrompt(
                emoji = "✓",
                title = stringResource(Res.string.shop_purchase_owned_title),
                body = ownedBodyFor(offer),
            )
        }
        VerticalSpacerD700()
        // Mode-specific CTA. Confirm button only fires for Available;
        // everything else gets a disabled informational label or a
        // close-only Owned variant.
        when (mode) {
            is PurchaseSheetMode.Available -> SheetButtons(
                confirmLabel = stringResource(Res.string.shop_purchase_buy_now),
                onConfirm = onConfirm,
                onCancel = onCancel,
                confirmEnabled = true,
            )
            is PurchaseSheetMode.Insufficient -> SheetButtons(
                confirmLabel = stringResource(
                    Res.string.shop_purchase_need_more_chips,
                    formatThousands(mode.shortBy),
                ),
                onConfirm = onConfirm,
                onCancel = onCancel,
                confirmEnabled = false,
            )
            is PurchaseSheetMode.Locked -> SheetButtons(
                confirmLabel = stringResource(Res.string.shop_purchase_locked),
                onConfirm = onConfirm,
                onCancel = onCancel,
                confirmEnabled = false,
            )
            is PurchaseSheetMode.Owned -> SheetButtons(
                confirmLabel = stringResource(Res.string.shop_purchase_close),
                onConfirm = onCancel,  // primary = close
                onCancel = onCancel,
                confirmEnabled = true,
                showCancel = false,
            )
        }
    }
}

/**
 * Centered emoji + title + body prompt block. Used for non-buyable
 * sheet modes (Locked, Owned) in place of the price + balance preview.
 * Visually consistent with the description card so the sheet has a
 * single "story-block" feel.
 */
@Composable
private fun StatusPrompt(
    emoji: String,
    title: String,
    body: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AppTheme.colors.surfaceRaised,
        contentColor = AppTheme.colors.contentSecondary,
        radius = Radii.Card,
        elevation = Elevation.None,
        onClick = {},
        bounceScale = 1f,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = emoji,
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.content,
            )
            VerticalSpacerD200()
            Text(
                text = title,
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )
            VerticalSpacerD100()
            Text(
                text = body,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BalancePreview(currentBalance: Long, cost: Long, canAfford: Boolean) {
    val newBalance = (currentBalance - cost).coerceAtLeast(0)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AppTheme.colors.surfaceRaised,
        contentColor = AppTheme.colors.contentSecondary,
        radius = Radii.Card,
        elevation = Elevation.None,
        onClick = {},
        bounceScale = 1f,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column {
            BalanceRow(
                label = stringResource(Res.string.shop_purchase_balance_current),
                amount = currentBalance,
            )
            VerticalSpacerD200()
            BalanceRow(
                label = stringResource(Res.string.shop_purchase_balance_after),
                amount = newBalance,
                amountColor = if (canAfford) AppTheme.colors.content else AppTheme.colors.danger,
            )
        }
    }
}

@Composable
private fun BalanceRow(
    label: String,
    amount: Long,
    amountColor: ColorResource = AppTheme.colors.content,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
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
    showCancel: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ButtonPrimary(
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = confirmLabel)
        }
        if (showCancel) {
            VerticalSpacerD300()
            ButtonSecondary(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(Res.string.shop_purchase_cancel))
            }
        }
    }
}

/**
 * "You own this" body copy, routed by what the user can actually DO
 * with the offer now that they own it. Three buckets:
 *  - Equippable cosmetics (felts, card backs, titles, table themes,
 *    tools): point at My items where the equip toggle lives.
 *  - Avatar packs (id prefix `avatars_`): point at Edit profile —
 *    owning the pack unlocks new emoji choices in the picker, not an
 *    equip toggle.
 *  - Emote packs (id prefix `emotes_`): point at the in-game tray —
 *    emotes are sent ad-hoc per hand, not pre-equipped.
 *
 * We route on id prefix instead of [Product.ChipOffer.isEquippable]
 * for the avatar/emote split because both are non-equippable but
 * direct the user to different places. The catalog id is the only
 * field that distinguishes them today; if a `kind` field gets added
 * later, swap this for a proper enum match.
 */
@Composable
private fun ownedBodyFor(offer: Product.ChipOffer): String = when {
    offer.id.startsWith("avatars_") ->
        stringResource(Res.string.shop_purchase_owned_body_avatars)
    offer.id.startsWith("emotes_") ->
        stringResource(Res.string.shop_purchase_owned_body_emotes)
    offer.isEquippable ->
        stringResource(Res.string.shop_purchase_owned_body_equippable)
    else ->
        stringResource(Res.string.shop_purchase_owned_body_default)
}

@Composable
private fun platformStoreName(): String =
    when (BuildInfo.platform) {
        Platform.iOS -> stringResource(Res.string.shop_purchase_store_app_store)
        Platform.Android -> stringResource(Res.string.shop_purchase_store_google_play)
    }

@Preview
@Composable
private fun PurchaseConfirmSheetPreview_IapPack() {
    PreviewContent {
        PurchaseConfirmSheet(
            sheetState = rememberBottomSheetState(),
            product = Product.ChipPack(
                id = "chip_pack_medium",
                title = "Tall Stack",
                subtitle = "30,000 chips",
                iconEmoji = "💰",
                featured = true,
                badge = "BEST VALUE",
                grantsChips = 30_000,
                store = StoreSku("chips_medium", "$4.99"),
            ),
            mode = PurchaseSheetMode.Available,
            chipBalance = 12_450,
            timeAnchor = null,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun PurchaseConfirmSheetPreview_ChipOfferAvailable() {
    PreviewContent {
        PurchaseConfirmSheet(
            sheetState = rememberBottomSheetState(),
            product = Product.ChipOffer(
                id = "emote_dance",
                title = "Victory Dance",
                subtitle = "Emote",
                description = "Send a celebration dance to the table when you win a hand — fills everyone's screen for a beat. Equip from your items.",
                iconEmoji = "💃",
                costChips = 2_500,
                grantsKey = "emote.dance",
            ),
            mode = PurchaseSheetMode.Available,
            chipBalance = 12_450,
            timeAnchor = null,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun PurchaseConfirmSheetPreview_ChipOfferInsufficient() {
    PreviewContent {
        PurchaseConfirmSheet(
            sheetState = rememberBottomSheetState(),
            product = Product.ChipOffer(
                id = "title_high_roller",
                title = "High Roller",
                subtitle = "Player title",
                description = "Rare title — shows under your name at the table for everyone to see. Equip from your items.",
                iconEmoji = "🏆",
                badge = "RARE",
                costChips = 25_000,
                grantsKey = "title.high_roller",
            ),
            mode = PurchaseSheetMode.Insufficient(shortBy = 23_500),
            chipBalance = 1_500,
            timeAnchor = null,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun PurchaseConfirmSheetPreview_ChipOfferLocked() {
    PreviewContent {
        PurchaseConfirmSheet(
            sheetState = rememberBottomSheetState(),
            product = Product.ChipOffer(
                id = "title_shark",
                title = "The Shark",
                subtitle = "Player title",
                description = "For the player who reads the table. Shows under your name.",
                iconEmoji = "🦈",
                costChips = 18_000,
                grantsKey = "title.shark",
                unlockLevel = 15,
            ),
            mode = PurchaseSheetMode.Locked(requiredLevel = 15),
            chipBalance = 25_000,
            timeAnchor = null,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun PurchaseConfirmSheetPreview_FeltAvailable() {
    PreviewContent {
        PurchaseConfirmSheet(
            sheetState = rememberBottomSheetState(),
            product = Product.ChipOffer(
                id = "felt_royal_red",
                title = "Royal Red Felt",
                subtitle = "Felt",
                description = "Deep crimson felt that paints under your cards at every table. Equip from your items.",
                iconEmoji = "🟥",
                costChips = 6_000,
                grantsKey = "felt.royal_red",
                isEquippable = true,
            ),
            mode = PurchaseSheetMode.Available,
            chipBalance = 12_450,
            timeAnchor = null,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun PurchaseConfirmSheetPreview_CardBackAvailable() {
    PreviewContent {
        PurchaseConfirmSheet(
            sheetState = rememberBottomSheetState(),
            product = Product.ChipOffer(
                id = "cardback_marble",
                title = "Marble",
                subtitle = "Card back",
                description = "Marble-pattern card back — replaces the default. Equip from your items.",
                iconEmoji = "🂠",
                costChips = 6_000,
                grantsKey = "cardback.marble",
                isEquippable = true,
            ),
            mode = PurchaseSheetMode.Available,
            chipBalance = 12_450,
            timeAnchor = null,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun PurchaseConfirmSheetPreview_ChipOfferOwned_Equippable() {
    PreviewContent {
        PurchaseConfirmSheet(
            sheetState = rememberBottomSheetState(),
            product = Product.ChipOffer(
                id = "cardback_marble",
                title = "Marble",
                subtitle = "Card back",
                description = "Marble-pattern card back — replaces the default. Equip from your items.",
                iconEmoji = "🂠",
                costChips = 6_000,
                grantsKey = "cardback.marble",
                isEquippable = true,
            ),
            mode = PurchaseSheetMode.Owned,
            chipBalance = 12_450,
            timeAnchor = null,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun PurchaseConfirmSheetPreview_ChipOfferOwned_AvatarPack() {
    // Owned avatar pack — body routes user to Edit profile rather
    // than My items, because avatar packs unlock emoji choices in
    // the picker (no equip toggle).
    PreviewContent {
        PurchaseConfirmSheet(
            sheetState = rememberBottomSheetState(),
            product = Product.ChipOffer(
                id = "avatars_animals",
                title = "Animal Avatars",
                subtitle = "Avatar pack · 8 emojis",
                description = "Unlocks 🐱 🐶 🐯 🐼 🦊 🐻 🦁 🐸 as avatar choices in your profile.",
                iconEmoji = "🦊",
                costChips = 4_000,
                grantsKey = "avatars.animals",
                isEquippable = false,
            ),
            mode = PurchaseSheetMode.Owned,
            chipBalance = 12_450,
            timeAnchor = null,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun PurchaseConfirmSheetPreview_ChipOfferOwned_EmotePack() {
    // Owned emote pack — body routes user to the in-game emote
    // tray; emotes are sent per-hand rather than pre-equipped.
    PreviewContent {
        PurchaseConfirmSheet(
            sheetState = rememberBottomSheetState(),
            product = Product.ChipOffer(
                id = "emotes_drama",
                title = "Drama Emote Pack",
                subtitle = "Emotes · 4 reactions",
                description = "Unlocks 💃 🧂 🎭 🤦 — send big, screen-filling reactions to the table.",
                iconEmoji = "💃",
                costChips = 3_500,
                grantsKey = "emotes.drama",
                isEquippable = false,
            ),
            mode = PurchaseSheetMode.Owned,
            chipBalance = 12_450,
            timeAnchor = null,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

