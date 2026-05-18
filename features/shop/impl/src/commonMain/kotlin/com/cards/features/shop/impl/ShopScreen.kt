package com.dangerfield.cards.features.shop.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.PurchaseState
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.StoreSku
import com.dangerfield.cards.libraries.ui.Elevation
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.BadgedBox
import com.dangerfield.cards.libraries.ui.components.BottomBarSpacer
import com.dangerfield.cards.libraries.ui.components.ChipBadge
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.CircularLoadingIndicator
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.Surface
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD100
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD400
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD700
import com.dangerfield.cards.system.VerticalSpacerD800
import com.dangerfield.cards.system.VerticalSpacerD1000
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Stateless shop screen — takes a [ShopState] + action callback and renders.
 *
 * Composition:
 *  - **Header**: title + lifetime chip balance.
 *  - **Featured hero card** (when the catalog has a featured chip pack):
 *    full-width gradient banner, big icon, badge, headline price.
 *  - **"Buy chips"** section: 2-column grid of remaining packs. Each card
 *    leads with its bonus state ("BEST VALUE", "+20%", etc.) when present.
 *  - **"Build your style"** section: 2-column grid of chip-purchasable
 *    items. Per-card states: BUY (affordable) / OWNED (in inventory) /
 *    DIMMED (can't afford).
 *  - **Purchase confirmation**: [PurchaseConfirmSheet] over everything else
 *    with item summary, balance preview (chip offers), and a chunky CTA.
 *
 * Top-level state branches: Loading → spinner. Empty → polished empty
 * state. Loaded → full layout above. Errors during refresh surface as an
 * inline retry banner; prior catalog stays visible the whole time.
 *
 * Stateless on purpose — every `@Preview` renders without DI; the entry
 * point is the only place that knows about the VM.
 *
 * Component split:
 *  - Sheet + sub-content + balance preview: [PurchaseConfirmSheet].
 *  - Icon tiles / shared pills / emoji placeholder map: [ShopComponents].
 */
@Composable
fun ShopScreen(
    state: ShopState,
    onAction: (ShopAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Screen(modifier = modifier) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.hasLoaded && state.isRefreshing -> LoadingState()
                state.hasLoaded && state.catalog.isEmpty -> EmptyState()
                else -> CatalogContent(state = state, onAction = onAction)
            }

            state.errorMessage?.let { message ->
                ErrorBanner(
                    message = message,
                    onRetry = { onAction(ShopAction.Refresh) },
                    onDismiss = { onAction(ShopAction.DismissError) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }

        // Purchase confirmation overlay — rendered last so it sits on top.
        state.pendingPurchase?.let { pending ->
            PurchaseConfirmSheet(
                pending = pending,
                chipBalance = state.chipBalance,
                onConfirm = { onAction(ShopAction.ConfirmPendingPurchase) },
                onDismiss = { onAction(ShopAction.DismissPendingPurchase) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------

@Composable
private fun CatalogContent(
    state: ShopState,
    onAction: (ShopAction) -> Unit,
) {
    val featured = state.catalog.chipPacks.firstOrNull { it.featured }
    val otherPacks = state.catalog.chipPacks.filterNot { it.id == featured?.id }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        VerticalSpacerD500()
        ShopHeader(chips = state.chipBalance)
        VerticalSpacerD700()

        featured?.let {
            FeaturedPackHero(
                pack = it,
                onClick = { onAction(ShopAction.RequestPurchase(it)) },
            )
            VerticalSpacerD800()
        }

        if (otherPacks.isNotEmpty()) {
            SectionHeader(
                title = "Buy chips",
                subtitle = "Stock up — every game uses chips.",
            )
            VerticalSpacerD400()
            ProductGrid(items = otherPacks) { pack ->
                ChipPackCard(
                    pack = pack,
                    onClick = { onAction(ShopAction.RequestPurchase(pack)) },
                )
            }
            VerticalSpacerD800()
        }

        if (state.catalog.chipOffers.isNotEmpty()) {
            SectionHeader(
                title = "Build your style",
                subtitle = "Emotes, table themes, and titles.",
            )
            VerticalSpacerD400()
            ProductGrid(items = state.catalog.chipOffers) { offer ->
                ChipOfferCard(
                    offer = offer,
                    owned = state.ownsProduct(offer.id),
                    pendingSync = state.inventory
                        .firstOrNull { it.productId == offer.id }
                        ?.state == PurchaseState.Pending,
                    canAfford = state.canAfford(offer),
                    onClick = { onAction(ShopAction.RequestPurchase(offer)) },
                )
            }
        }

        VerticalSpacerD1000()
        BottomBarSpacer()
    }
}

@Composable
private fun ShopHeader(chips: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Shop",
                typography = AppTheme.typography.Heading.H1000,
                color = AppTheme.colors.text,
            )
            VerticalSpacerD100()
            Text(
                text = "Spend chips. Stock up. Flex.",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
            )
        }
        ChipBadge(amount = chips)
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column {
        Text(
            text = title,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.text,
        )
        subtitle?.let {
            VerticalSpacerD100()
            Text(
                text = it,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * Manual 2-column grid (not LazyVerticalGrid) — we're inside a verticalScroll
 * Column so a nested-lazy isn't allowed, and the catalog list is small so
 * the perf trade is irrelevant.
 *
 * **Equal-height rows**: the Row gets `IntrinsicSize.Max` and each cell
 * `fillMaxHeight`, so a row with a tall card + a short one doesn't leave
 * the short one floating mid-air. Without this, a "+20%" badge or extra
 * subtitle line in one card pushes its neighbor's price into wonky
 * alignment.
 */
@Composable
private fun <T> ProductGrid(
    items: List<T>,
    cell: @Composable (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { cell(row[0]) }
                if (row.size > 1) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) { cell(row[1]) }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Featured hero card
// ---------------------------------------------------------------------------

@Composable
private fun FeaturedPackHero(pack: Product.ChipPack, onClick: () -> Unit) {
    // Gradient backdrop — accentSecondary (purple) → accentPrimary (blue),
    // giving the hero its own visual zone vs. the neutral cards below.
    val gradient = Brush.linearGradient(
        colors = listOf(
            AppTheme.colors.accentSecondary.color,
            AppTheme.colors.accentPrimary.color,
        ),
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = null,
        contentColor = AppTheme.colors.onAccentPrimary,
        radius = Radii.Card,
        elevation = Elevation.Card,
        onClick = onClick,
        bounceScale = 0.97f,
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(horizontal = 22.dp, vertical = 22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeroProductIcon(iconKey = pack.iconKey)
                Spacer(modifier = Modifier.size(Dimension.D700))
                Column(modifier = Modifier.weight(1f)) {
                    pack.badge?.let {
                        BadgePill(text = it, accent = ColorResource.White)
                        VerticalSpacerD200()
                    }
                    Text(
                        text = pack.title,
                        typography = AppTheme.typography.Heading.H800,
                        color = AppTheme.colors.onAccentPrimary,
                    )
                    VerticalSpacerD100()
                    Text(
                        text = pack.subtitle,
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.onAccentPrimary,
                        modifier = Modifier.alpha(0.85f),
                    )
                    VerticalSpacerD400()
                    Text(
                        text = pack.store.fallbackPriceDisplay,
                        typography = AppTheme.typography.Heading.H700,
                        color = AppTheme.colors.onAccentPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroProductIcon(iconKey: String) {
    Box(
        modifier = Modifier
            .size(86.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(AppTheme.colors.background.color.copy(alpha = 0.18f))
            .border(
                width = 2.dp,
                color = AppTheme.colors.onAccentPrimary.color.copy(alpha = 0.25f),
                shape = RoundedCornerShape(22.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emojiForIconKey(iconKey),
            typography = AppTheme.typography.Heading.H1100,
            color = AppTheme.colors.onAccentPrimary,
        )
    }
}

// ---------------------------------------------------------------------------
// Chip pack card (grid item)
// ---------------------------------------------------------------------------

/**
 * Cards with a [Product.badge] wrap their Surface in a [BadgedBox] so the
 * badge can hang off the top-right corner without disturbing the card's
 * interior layout. The translation nudges the badge in toward the card
 * just enough that it can't get clipped by the screen's horizontal padding
 * on the rightmost column.
 *
 * Without [BadgedBox] we had a hand-rolled `CornerBadge` that used a flat
 * `offset(x = 6, y = -6)` — works for centered cards but the right column's
 * badge crept under the parent Column's padding. Letting the DS primitive
 * handle the math means the same call site works on any grid.
 */
@Composable
private fun ChipPackCard(pack: Product.ChipPack, onClick: () -> Unit) {
    val card: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            color = AppTheme.colors.surfacePrimary,
            contentColor = AppTheme.colors.onSurfacePrimary,
            radius = Radii.Card,
            elevation = Elevation.Card,
            onClick = onClick,
            bounceScale = 0.95f,
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProductIcon(iconKey = pack.iconKey, tone = IconTone.Gold)
                VerticalSpacerD400()
                Text(
                    text = pack.title,
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.text,
                    textAlign = TextAlign.Center,
                )
                VerticalSpacerD100()
                Text(
                    text = pack.subtitle,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                // Spacer-with-weight pushes the price to the bottom edge so
                // every card in the row aligns regardless of how much
                // content sits above. Works in concert with the parent grid's
                // IntrinsicSize.Max + fillMaxHeight chain.
                Spacer(modifier = Modifier.weight(1f, fill = true))
                Spacer(modifier = Modifier.height(Dimension.D400))
                Text(
                    text = pack.store.fallbackPriceDisplay,
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.text,
                )
            }
        }
    }

    val packBadge = pack.badge
    if (packBadge == null) {
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) { card() }
    } else {
        BadgedBox(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            contentRadius = Radii.Card,
            // Pull the badge slightly back into the card so the
            // overhanging portion can't get cropped by the screen's 20.dp
            // horizontal padding on the right-column cell.
            badgeTranslation = DpOffset(x = (-30).dp, y = 2.dp),
            badge = { OverhangBadge(text = packBadge, accent = ColorResource.Amber600) },
            content = { card() },
        )
    }
}

// ---------------------------------------------------------------------------
// Chip offer card (grid item) — owned / affordable / dimmed
// ---------------------------------------------------------------------------

@Composable
private fun ChipOfferCard(
    offer: Product.ChipOffer,
    owned: Boolean,
    pendingSync: Boolean,
    canAfford: Boolean,
    onClick: () -> Unit,
) {
    val interactionAlpha = when {
        owned -> 1f
        canAfford -> 1f
        else -> 0.55f
    }
    val tappable = !owned && canAfford
    val card: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight().alpha(interactionAlpha),
            color = AppTheme.colors.surfacePrimary,
            contentColor = AppTheme.colors.onSurfacePrimary,
            radius = Radii.Card,
            elevation = Elevation.Card,
            onClick = if (tappable) onClick else ({}),
            bounceScale = if (tappable) 0.95f else 1f,
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    ProductIcon(iconKey = offer.iconKey, tone = IconTone.Accent)
                    if (owned) OwnedCheck()
                }
                VerticalSpacerD400()
                Text(
                    text = offer.title,
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.text,
                    textAlign = TextAlign.Center,
                )
                VerticalSpacerD100()
                Text(
                    text = offer.subtitle,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                // Push the footer to the bottom edge — see ChipPackCard for
                // the rationale. Keeps every card in a row aligned at the
                // footer regardless of badge / subtitle length variance.
                Spacer(modifier = Modifier.weight(1f, fill = true))
                Spacer(modifier = Modifier.height(Dimension.D400))
                when {
                    owned -> OwnedFooter(syncing = pendingSync)
                    else -> ChipCostFooter(cost = offer.costChips, canAfford = canAfford)
                }
            }
        }
    }

    // Owned items skip the corner badge: the OwnedCheck overlay on the icon
    // already communicates state and a second pill on top of it just clutters.
    val offerBadge = offer.badge
    if (offerBadge == null || owned) {
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) { card() }
    } else {
        BadgedBox(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            contentRadius = Radii.Card,
            badgeTranslation = DpOffset(x = (-6).dp, y = 2.dp),
            badge = { OverhangBadge(text = offerBadge, accent = ColorResource.Red400) },
            content = { card() },
        )
    }
}

@Composable
private fun OwnedCheck() {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(AppTheme.colors.status.okay.color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "✓",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun OwnedFooter(syncing: Boolean) {
    val label = if (syncing) "OWNED · SYNCING" else "OWNED"
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(AppTheme.colors.status.okay.color.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.status.okay,
        )
    }
}

@Composable
private fun ChipCostFooter(cost: Long, canAfford: Boolean) {
    val bg = if (canAfford) AppTheme.colors.surfaceTertiary.color else AppTheme.colors.surfaceDisabled.color
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ChipCoin (DS) — matches the gold coin used in the header / table pot
        // / stack so users get one consistent "this means chips" affordance.
        ChipCoin(size = 16.dp, textTypography = AppTheme.typography.Body.B400)
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = formatChips(cost),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
        )
    }
}

// ---------------------------------------------------------------------------
// Top-level states + overlays
// ---------------------------------------------------------------------------

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularLoadingIndicator()
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.surfaceSecondary.color),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "🛍️",
                typography = AppTheme.typography.Heading.H900,
                color = AppTheme.colors.text,
            )
        }
        VerticalSpacerD700()
        Text(
            text = "Shop is empty for now",
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.text,
        )
        VerticalSpacerD200()
        Text(
            text = "New chip packs and cosmetics drop weekly. Check back soon.",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
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
    Surface(
        modifier = modifier,
        color = AppTheme.colors.danger,
        contentColor = AppTheme.colors.text,
        radius = Radii.Card,
        elevation = Elevation.Card,
        onClick = onDismiss,
        bounceScale = 1f,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Couldn't load shop",
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.text,
                )
                VerticalSpacerD100()
                Text(
                    text = message,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.text,
                    modifier = Modifier.alpha(0.85f),
                )
            }
            Spacer(modifier = Modifier.size(Dimension.D300))
            ButtonPrimary(onClick = onRetry, size = ButtonSize.Small) {
                Text(text = "Retry")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews — every meaningful state.
// ---------------------------------------------------------------------------

@Preview
@Composable
private fun ShopScreenPreview_Loading() {
    PreviewContent {
        ShopScreen(
            state = ShopState(isRefreshing = true, hasLoaded = false, chipBalance = 12_450),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun ShopScreenPreview_Empty() {
    PreviewContent {
        ShopScreen(
            state = ShopState(hasLoaded = true, chipBalance = 12_450),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun ShopScreenPreview_FullCatalog() {
    PreviewContent {
        ShopScreen(
            state = ShopState(
                hasLoaded = true,
                chipBalance = 12_450,
                catalog = previewFullCatalog(),
            ),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun ShopScreenPreview_MixedOwnedAndDisabled() {
    val catalog = previewFullCatalog()
    PreviewContent {
        ShopScreen(
            state = ShopState(
                hasLoaded = true,
                chipBalance = 3_000,
                catalog = catalog,
                inventory = listOf(
                    InventoryItem(
                        productId = "emote_dance",
                        state = PurchaseState.Confirmed,
                        purchasedAtEpochMs = 0,
                        costChipsAtPurchase = 2_500,
                    ),
                    InventoryItem(
                        productId = "cardback_marble",
                        state = PurchaseState.Pending,
                        purchasedAtEpochMs = 0,
                        costChipsAtPurchase = 6_000,
                    ),
                ),
                ownedProductIds = setOf("emote_dance", "cardback_marble"),
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
                catalog = previewFullCatalog(),
                errorMessage = "Offline — pulled the cached shop.",
            ),
            onAction = {},
        )
    }
}

private fun previewFullCatalog(): ProductCatalog = ProductCatalog(
    chipPacks = listOf(
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
            id = "chip_pack_small",
            title = "Pocket Stack",
            subtitle = "5,000 chips",
            iconKey = "chips_small",
            grantsChips = 5_000,
            store = StoreSku("chips_small", "$0.99"),
        ),
        Product.ChipPack(
            id = "chip_pack_large",
            title = "Whale Stack",
            subtitle = "80,000 chips",
            iconKey = "chips_large",
            badge = "+20%",
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
    chipOffers = listOf(
        Product.ChipOffer(
            id = "emote_dance",
            title = "Victory Dance",
            subtitle = "Emote",
            description = "Send a celebration dance to the table when you win a hand — fills everyone's screen for a beat. Equip from your items.",
            iconKey = "emote_dance",
            costChips = 2_500,
            grantsKey = "emote.dance",
        ),
        Product.ChipOffer(
            id = "emote_tilt",
            title = "Salty Shake",
            subtitle = "Emote",
            iconKey = "emote_tilt",
            costChips = 2_500,
            grantsKey = "emote.tilt",
        ),
        Product.ChipOffer(
            id = "cardback_marble",
            title = "Marble",
            subtitle = "Card back",
            iconKey = "cardback_marble",
            badge = "POPULAR",
            costChips = 6_000,
            grantsKey = "cardback.marble",
        ),
        Product.ChipOffer(
            id = "cardback_neon",
            title = "Neon Lines",
            subtitle = "Card back",
            iconKey = "cardback_neon",
            costChips = 6_000,
            grantsKey = "cardback.neon",
        ),
        Product.ChipOffer(
            id = "table_neon",
            title = "Neon Table",
            subtitle = "Table theme",
            iconKey = "table_neon",
            featured = true,
            badge = "NEW",
            costChips = 12_000,
            grantsKey = "table.neon",
        ),
        Product.ChipOffer(
            id = "title_bluff_master",
            title = "Bluff Master",
            subtitle = "Player title",
            iconKey = "title_bluff_master",
            costChips = 15_000,
            grantsKey = "title.bluff_master",
        ),
    ),
)
