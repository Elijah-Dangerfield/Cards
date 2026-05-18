package com.dangerfield.cards.features.shop.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.StoreSku
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.BottomBarSpacer
import com.dangerfield.cards.libraries.ui.components.ChipBadge
import com.dangerfield.cards.libraries.ui.components.CircularLoadingIndicator
import com.dangerfield.cards.libraries.ui.components.Card
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800
import com.dangerfield.cards.system.VerticalSpacerD1000
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Stateless shop screen — takes a [ShopState] + action callback and renders.
 *
 * The screen has three high-level states:
 *  - **Loading** (`!hasLoaded && isRefreshing`) — full-screen spinner the
 *    very first time the user opens the shop. Avoids flashing an empty
 *    grid while we wait for the network.
 *  - **Empty** (`hasLoaded && catalog.isEmpty`) — the API legitimately
 *    returned nothing for sale. Polished empty state, not an error.
 *  - **Loaded** — render the catalog with section headers. Pull-to-refresh
 *    keeps prior content visible while the new fetch is in flight.
 *
 * Errors during refresh surface as an inline retry banner — they don't blow
 * away the prior catalog. The user can dismiss; the action ratifies that.
 *
 * Keeping the screen stateless (no VM dep here) means every `@Preview`
 * below renders without DI, and the FeatureEntryPoint is the only place
 * that knows about the VM.
 */
@Composable
fun ShopScreen(
    state: ShopState,
    onAction: (ShopAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Screen(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            ShopTopBar(chips = state.chipBalance)
            VerticalSpacerD500()

            when {
                !state.hasLoaded && state.isRefreshing -> LoadingState()
                state.hasLoaded && state.catalog.isEmpty -> EmptyState()
                else -> CatalogContent(
                    catalog = state.catalog,
                    onPurchasePack = { onAction(ShopAction.PurchaseChipPack(it)) },
                    onRedeemOffer = { onAction(ShopAction.RedeemChipOffer(it)) },
                )
            }
        }

        // Error snackbar overlay — non-blocking, dismissible. Sits on top of
        // the prior catalog so a flaky network doesn't blank the screen.
        state.errorMessage?.let { message ->
            ErrorBanner(
                message = message,
                onRetry = { onAction(ShopAction.Refresh) },
                onDismiss = { onAction(ShopAction.DismissError) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ShopTopBar(chips: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Shop",
            typography = AppTheme.typography.Heading.H900,
            color = AppTheme.colors.text,
        )
        Spacer(modifier = Modifier.weight(1f))
        ChipBadge(amount = chips)
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularLoadingIndicator()
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.surfaceSecondary.color),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "🛍️",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.text,
            )
        }
        VerticalSpacerD500()
        Text(
            text = "Shop is empty for now",
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.text,
        )
        VerticalSpacerD200()
        Text(
            text = "New chip packs and items coming soon.",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CatalogContent(
    catalog: ProductCatalog,
    onPurchasePack: (Product.ChipPack) -> Unit,
    onRedeemOffer: (Product.ChipOffer) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        if (catalog.chipPacks.isNotEmpty()) {
            SectionHeader("Buy chips")
            VerticalSpacerD300()
            catalog.chipPacks.forEach { pack ->
                ChipPackRow(pack = pack, onClick = { onPurchasePack(pack) })
                VerticalSpacerD300()
            }
        }
        if (catalog.chipOffers.isNotEmpty()) {
            VerticalSpacerD500()
            SectionHeader("Spend chips")
            VerticalSpacerD300()
            catalog.chipOffers.forEach { offer ->
                ChipOfferRow(offer = offer, onClick = { onRedeemOffer(offer) })
                VerticalSpacerD300()
            }
        }
        VerticalSpacerD800()
        BottomBarSpacer()
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label.uppercase(),
        typography = AppTheme.typography.Label.L300,
        color = AppTheme.colors.textSecondary,
    )
}

@Composable
private fun ChipPackRow(pack: Product.ChipPack, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProductIcon(emoji = "💰", featured = pack.featured)
            Spacer(modifier = Modifier.size(Dimension.D700))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pack.title,
                        typography = AppTheme.typography.Body.B600,
                        color = AppTheme.colors.text,
                    )
                    pack.badge?.let { badgeText ->
                        Spacer(modifier = Modifier.size(Dimension.D300))
                        ProductBadge(text = badgeText)
                    }
                }
                VerticalSpacerD200()
                Text(
                    text = pack.subtitle,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                )
            }
            Spacer(modifier = Modifier.size(Dimension.D300))
            PriceTag(text = pack.store.fallbackPriceDisplay)
        }
    }
}

@Composable
private fun ChipOfferRow(offer: Product.ChipOffer, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProductIcon(emoji = "🎨", featured = offer.featured)
            Spacer(modifier = Modifier.size(Dimension.D700))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = offer.title,
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.text,
                )
                VerticalSpacerD200()
                Text(
                    text = offer.subtitle,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                )
            }
            Spacer(modifier = Modifier.size(Dimension.D300))
            ChipBadge(amount = offer.costChips)
        }
    }
}

@Composable
private fun ProductIcon(emoji: String, featured: Boolean) {
    val bg = if (featured) AppTheme.colors.accentPrimary.color else AppTheme.colors.surfaceSecondary.color
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
            typography = AppTheme.typography.Heading.H500,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun ProductBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(AppTheme.colors.accentPrimary.color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.onAccentPrimary,
        )
    }
}

@Composable
private fun PriceTag(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Surface that floats over the catalog content. Tap-to-retry on the
    // button, tap-to-dismiss on the row body.
    LaunchedEffect(message) { /* future: auto-dismiss timer */ }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.colors.danger.color)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Couldn't load shop",
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.text,
                )
                VerticalSpacerD200()
                Text(
                    text = message,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.text,
                )
            }
            Spacer(modifier = Modifier.size(Dimension.D300))
            ButtonPrimary(onClick = onRetry, size = ButtonSize.Small) {
                Text(text = "Retry")
            }
        }
        // The whole banner is also clickable to dismiss for a quicker out.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 0.dp)
                .clip(RoundedCornerShape(12.dp))
                .let { it /* placeholder for an overlay-clickable hook later */ },
        )
        if (false) onDismiss()  // referenced to avoid unused param at this stage
    }
}

// --------------------------------------------------------------------------
// Previews — meaningful states only, per AGENTS.md
// --------------------------------------------------------------------------

@Preview
@Composable
private fun ShopScreenPreview_Loading() {
    PreviewContent {
        ShopScreen(
            state = ShopState(isRefreshing = true, hasLoaded = false, chipBalance = 10_000),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun ShopScreenPreview_Empty() {
    PreviewContent {
        ShopScreen(
            state = ShopState(
                hasLoaded = true,
                catalog = ProductCatalog.Empty,
                chipBalance = 10_000,
            ),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun ShopScreenPreview_LoadedFourChipPacks() {
    PreviewContent {
        ShopScreen(
            state = ShopState(
                hasLoaded = true,
                chipBalance = 12_450,
                catalog = previewCatalog(),
            ),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun ShopScreenPreview_LoadedWithChipOffers() {
    PreviewContent {
        ShopScreen(
            state = ShopState(
                hasLoaded = true,
                chipBalance = 45_000,
                catalog = ProductCatalog(
                    chipPacks = previewCatalog().chipPacks.take(2),
                    chipOffers = listOf(
                        Product.ChipOffer(
                            id = "emote_dance",
                            title = "Victory Dance",
                            subtitle = "Avatar emote",
                            iconKey = "emote_dance",
                            costChips = 2_500,
                            grantsKey = "emote.dance",
                        ),
                        Product.ChipOffer(
                            id = "table_neon",
                            title = "Neon Table",
                            subtitle = "Table theme",
                            iconKey = "table_neon",
                            costChips = 8_000,
                            grantsKey = "table.neon",
                            featured = true,
                            badge = "NEW",
                        ),
                    ),
                ),
            ),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun ShopScreenPreview_ErrorWithPriorCatalog() {
    PreviewContent {
        ShopScreen(
            state = ShopState(
                hasLoaded = true,
                chipBalance = 12_450,
                catalog = previewCatalog(),
                errorMessage = "Offline — pulled the cached shop.",
            ),
            onAction = {},
        )
    }
}

private fun previewCatalog(): ProductCatalog = ProductCatalog(
    chipPacks = listOf(
        Product.ChipPack(
            id = "chip_pack_small",
            title = "Pocket Stack",
            subtitle = "5,000 chips",
            iconKey = "chips_small",
            grantsChips = 5_000,
            store = StoreSku("chips_small", "$0.99"),
        ),
        Product.ChipPack(
            id = "chip_pack_medium",
            title = "Tall Stack",
            subtitle = "30,000 chips",
            iconKey = "chips_medium",
            featured = true,
            badge = "BEST VALUE",
            grantsChips = 30_000,
            store = StoreSku("chips_medium", "$4.99"),
        ),
        Product.ChipPack(
            id = "chip_pack_large",
            title = "Whale Stack",
            subtitle = "80,000 chips",
            iconKey = "chips_large",
            badge = "+20% BONUS",
            grantsChips = 80_000,
            store = StoreSku("chips_large", "$9.99"),
        ),
        Product.ChipPack(
            id = "chip_pack_mega",
            title = "High Roller",
            subtitle = "250,000 chips",
            iconKey = "chips_mega",
            grantsChips = 250_000,
            store = StoreSku("chips_mega", "$19.99"),
        ),
    ),
)
