package com.dangerfield.cards.features.shop.impl

import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.shop_also_earnable_hint
import cards.libraries.resources.generated.resources.shop_empty_subtitle
import cards.libraries.resources.generated.resources.shop_empty_title
import cards.libraries.resources.generated.resources.shop_error_retry
import cards.libraries.resources.generated.resources.shop_error_title
import cards.libraries.resources.generated.resources.shop_get_chips_footnote
import cards.libraries.resources.generated.resources.shop_get_chips_title
import cards.libraries.resources.generated.resources.shop_header_subtitle
import cards.libraries.resources.generated.resources.shop_header_title
import cards.libraries.resources.generated.resources.shop_idea_footer_button
import cards.libraries.resources.generated.resources.shop_need_chips_more
import cards.libraries.resources.generated.resources.shop_owned_badge
import cards.libraries.resources.generated.resources.shop_personal_cosmetic_hint
import cards.libraries.resources.generated.resources.shop_unlocks_at_level
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import com.dangerfield.cards.libraries.ui.components.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import com.dangerfield.cards.features.shop.ShopCategory
import kotlin.math.roundToInt
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.CosmeticTier
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.PurchaseState
import com.dangerfield.cards.libraries.cards.isPersonalCosmetic
import com.dangerfield.cards.libraries.cards.tierForProductId
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.StoreSku
import com.dangerfield.cards.libraries.ui.Elevation
import com.dangerfield.cards.libraries.ui.PreviewBottomBar
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.border
import com.dangerfield.cards.libraries.ui.components.BadgePlacement
import com.dangerfield.cards.libraries.ui.components.BadgedBox
import com.dangerfield.cards.libraries.ui.components.poker.CosmeticPreview
import com.dangerfield.cards.libraries.ui.components.ChipBadge
import com.dangerfield.cards.libraries.ui.components.header.SectionHeader
import com.dangerfield.cards.libraries.ui.components.BottomBarSpacer
import com.dangerfield.cards.libraries.ui.components.ChipCoinAmount
import com.dangerfield.cards.libraries.ui.components.CircularLoadingIndicator
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.Surface
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.libraries.ui.screenHorizontalInsets
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.thenIf
import com.dangerfield.cards.system.VerticalSpacerD100
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD400
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD700
import com.dangerfield.cards.system.VerticalSpacerD800
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopScreen(
    state: ShopState,
    onAction: (ShopAction) -> Unit,
    onProductTap: (productId: String) -> Unit,
    onIdeaTap: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    Screen(modifier = modifier) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .screenContentPadding(
                    paddingValues = padding,
                    includeHorizontalInsets = false,
                ),
        ) {
            when {
                !state.hasLoaded && state.isRefreshing -> LoadingState()
                state.hasLoaded && state.catalog.isEmpty -> EmptyState()
                else -> {
                    // PullToRefreshBox owns the gesture + indicator. We
                    // bind it to `isRefreshing` so the spinner stays
                    // visible during the background refetch — the
                    // cached catalog continues to render underneath
                    // (stale-while-revalidate).
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = { onAction(ShopAction.Refresh(force = true)) },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        CatalogContent(
                            state = state,
                            onAction = onAction,
                            onProductTap = onProductTap,
                            onIdeaTap = onIdeaTap,
                            scrollState = scrollState,
                        )
                    }
                }
            }

            if (state.hasRefreshError) {
                ErrorBanner(
                    message = stringResource(Res.string.shop_error_title),
                    onRetry = { onAction(ShopAction.Refresh(force = true)) },
                    onDismiss = { onAction(ShopAction.DismissError) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }

            // Wallet balance pinned to the top-right like a FAB — it stays put
            // while the catalog scrolls under it. Only while the catalog shows.
            if (state.hasLoaded && !state.catalog.isEmpty) {
                ChipBadge(
                    amount = state.chipBalance,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(screenHorizontalInsets)
                        .padding(top = Dimension.D500)
                        // Lift the floating wallet off the scrolling catalog.
                        .shadow(elevation = 6.dp, shape = Radii.Round.shape),
                )
            }
        }
        // The purchase confirmation sheet used to render here as
        // overlay UI driven by `state.pendingPurchase`. It's now its
        // own navigation destination (`ShopProductSheetRoute`) mounted
        // via `NavGraphBuilder.bottomSheet` in `ShopFeatureEntryPoint`,
        // so this screen is purely the grid. Tap → `onProductTap` →
        // the entry point navigates to the sheet route.
    }
}

// ---------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------

@Composable
private fun CatalogContent(
    state: ShopState,
    onAction: (ShopAction) -> Unit,
    onProductTap: (productId: String) -> Unit,
    onIdeaTap: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
) {
    // Content offset of each category's section header, captured as the
    // grid lays out. Drives the deep-link scroll below. Keyed by the
    // public ShopCategory so a cross-tab "land on avatars" intent resolves
    // to the right shelf regardless of catalog order.
    val sectionOffsets = remember { mutableStateMapOf<ShopCategory, Int>() }

    // Scroll to the requested section once it's measured, then clear the
    // pending target so a later recompose doesn't re-scroll. Re-runs when
    // the target offset resolves from null → value (section measured after
    // a cold deep-link arrived before the catalog hydrated).
    val pendingCategory = state.pendingScrollCategory
    val targetOffset = pendingCategory?.let { sectionOffsets[it] }
    LaunchedEffect(pendingCategory, targetOffset) {
        if (pendingCategory == null) return@LaunchedEffect
        val offset = targetOffset ?: return@LaunchedEffect
        scrollState.animateScrollTo(offset)
        onAction(ShopAction.ScrollConsumed)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(screenHorizontalInsets),
    ) {
        VerticalSpacerD500()
        // First-sync hydrate normally lands during the splash gate; rare
        // race where shop opens before the chip sync resolves shows 0
        // rather than passing null through every leaf. UI polish for a
        // true loading state is a separate follow-up.
        // Nullable on purpose: null = balance hasn't hydrated yet (first launch
        // or post account-switch wipe). The pill renders "—" rather than a
        // fake "0" until the sync lands — same as Home.
        ShopHeader()
        VerticalSpacerD700()

        GetChipsSection(
            packs = state.catalog.chipPacks,
            onProductTap = onProductTap,
        )
        VerticalSpacerD800()

        // Cosmetics grouped by product type so the shop reads as organized
        // shelves (Card backs, Felts, Table themes, Emote packs, …) rather
        // than one undifferentiated grid. Catalog sort order is preserved
        // within each group.
        val offerSections = ShopSectionOrder.mapNotNull { section ->
            state.catalog.chipOffers
                .filter { shopSectionFor(it.id) == section }
                .takeIf { it.isNotEmpty() }
                ?.let { section to it }
        }
        offerSections.forEachIndexed { index, (section, items) ->
            if (index > 0) VerticalSpacerD800()
            SectionHeader(
                title = section.title,
                modifier = section.category?.let { category ->
                    Modifier.onGloballyPositioned { coordinates ->
                        sectionOffsets[category] = coordinates.positionInParent().y.roundToInt()
                    }
                } ?: Modifier,
            )
            VerticalSpacerD400()
            ProductGrid(items = items) { offer ->
                ChipOfferCard(
                    offer = offer,
                    cardState = state.classify(offer),
                    tier = tierForProductId(offer.id),
                    timeAnchor = state.timeAnchor,
                    onExpired = { onAction(ShopAction.Refresh(force = true)) },
                    onClick = { onProductTap(offer.id) },
                )
            }
        }

        VerticalSpacerD700()
        IdeaFooter(onClick = onIdeaTap)
        VerticalSpacerD500()
        BottomBarSpacer()
    }
}

/**
 * Subtle text-button at the bottom of the shop scroll. Opens the general
 * feedback sheet — surfaces the channel without taking up real estate
 * meant for product cards.
 */
@Composable
private fun IdeaFooter(onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Button(
            onClick = onClick,
            size = ButtonSize.Small,
            style = ButtonStyle.Text,
        ) {
            Text(stringResource(Res.string.shop_idea_footer_button))
        }
    }
}

@Composable
private fun ShopHeader() {
    Column {
        Text(
            text = stringResource(Res.string.shop_header_title),
            typography = AppTheme.typography.Heading.H1000,
            color = AppTheme.colors.content,
        )
        VerticalSpacerD100()
        Text(
            text = stringResource(Res.string.shop_header_subtitle),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
        )
    }
}

/**
 * Storefront shelves for the chip-offer catalog, keyed off the product-id
 * prefix convention (`cardback_`, `felt_`, `table_`, `emotes_`, `avatars_`,
 * `tool_`). Titles are unlock-only (earned, not bought) so they never reach
 * the shop and get no shelf here.
 */
private enum class ShopSection(val title: String, val category: ShopCategory?) {
    Boosts("Boosts", ShopCategory.Boosts),
    CardBacks("Card backs", ShopCategory.CardBacks),
    Felts("Felts", ShopCategory.Felts),
    Tables("Table themes", ShopCategory.Tables),
    Emotes("Emote packs", ShopCategory.Emotes),
    Avatars("Avatar packs", ShopCategory.Avatars),
    Tools("Tools", ShopCategory.Tools),
    Other("More", null),
}

private val ShopSectionOrder = listOf(
    ShopSection.Boosts,
    ShopSection.CardBacks,
    ShopSection.Felts,
    ShopSection.Tables,
    ShopSection.Emotes,
    ShopSection.Avatars,
    ShopSection.Tools,
    ShopSection.Other,
)

private fun shopSectionFor(productId: String): ShopSection = when {
    productId.startsWith("boost_") -> ShopSection.Boosts
    productId.startsWith("cardback_") -> ShopSection.CardBacks
    productId.startsWith("felt_") -> ShopSection.Felts
    productId.startsWith("table_") -> ShopSection.Tables
    productId.startsWith("emotes_") -> ShopSection.Emotes
    productId.startsWith("avatars_") -> ShopSection.Avatars
    productId.startsWith("tool_") -> ShopSection.Tools
    else -> ShopSection.Other
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
// Get chips — responsive 3-tier chip-pack section
// ---------------------------------------------------------------------------

/**
 * The "Get chips" section: a responsive chip-pack ladder with the featured
 * pack highlighted as the middle "POPULAR" tier. On wide-enough screens the
 * three packs render side-by-side (the popular one raised + gold); on thin
 * screens they stack into roomy full-width rows so nothing gets cramped.
 *
 * A "best value" / promo hero banner can slot in directly above the three
 * options (see the marker below) without disturbing this layout.
 */
@Composable
private fun GetChipsSection(
    packs: List<Product.ChipPack>,
    onProductTap: (productId: String) -> Unit,
) {
    if (packs.isEmpty()) return
    SectionHeader(title = stringResource(Res.string.shop_get_chips_title))
    VerticalSpacerD500()

    // Hero banner slot — intentionally empty for now. Drop a promo/best-value
    // banner here when one exists; it sits above the main three options.

    BoxWithConstraints {
        // Three side-by-side cards only read well with enough width; below the
        // breakpoint we stack into full-width rows.
        if (maxWidth >= ThreeUpMinWidth) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimension.D400),
                verticalAlignment = Alignment.Bottom,
            ) {
                packs.forEach { pack ->
                    ChipTierCard(
                        pack = pack,
                        isPopular = pack.featured,
                        onClick = { onProductTap(pack.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Dimension.D400)) {
                packs.forEach { pack ->
                    ChipTierRowCard(
                        pack = pack,
                        isPopular = pack.featured,
                        onClick = { onProductTap(pack.id) },
                    )
                }
            }
        }
    }

    VerticalSpacerD500()
    Text(
        text = stringResource(Res.string.shop_get_chips_footnote),
        typography = AppTheme.typography.Body.B400,
        color = AppTheme.colors.contentSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Below this usable width the three-up row gets cramped, so we stack into
 * full-width rows instead. Tuned so normal phones (~320dp+ usable) keep the
 * side-by-side ladder and only genuinely thin / split-screen cases stack.
 */
private val ThreeUpMinWidth = 300.dp

/**
 * Soft gold wash for chip-pack cards — every buyable chip tier wears it so
 * the grid reads as one warm "chips" family (was POPULAR-only). The popular
 * tier still stands apart via its accent border + overhang badge + taller
 * card, not a unique fill.
 */
@Composable
private fun chipPackGradient(): Brush = Brush.verticalGradient(
    colors = listOf(
        AppTheme.colors.accentPrimary.color.copy(alpha = 0.30f),
        AppTheme.colors.accentPrimary.color.copy(alpha = 0.10f),
    ),
)

@Composable
private fun PricePill(text: String) {
    Box(
        modifier = Modifier
            .clip(Radii.Round.shape)
            .background(AppTheme.colors.background.color)
            .padding(horizontal = Dimension.D500, vertical = Dimension.D200),
    ) {
        Text(
            text = text,
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.content,
        )
    }
}

/** Vertical tier card for the wide (3-up) layout. */
@Composable
private fun ChipTierCard(
    pack: Product.ChipPack,
    isPopular: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gradient = chipPackGradient()
    val inner: @Composable () -> Unit = {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .thenIf(isPopular) {
                    border(2.dp, AppTheme.colors.accentPrimary.color, Radii.Card.shape)
                },
            color = AppTheme.colors.surface,
            contentColor = AppTheme.colors.content,
            radius = Radii.Card,
            elevation = Elevation.Card,
            onClick = onClick,
            bounceScale = 0.95f,
            contentPadding = PaddingValues(0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(gradient)
                    // The popular tier is taller (more vertical breathing room)
                    // so it visibly rises above its neighbors in the row.
                    .padding(
                        horizontal = Dimension.D400,
                        vertical = if (isPopular) Dimension.D850 else Dimension.D600,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProductIcon(emoji = pack.iconEmoji, tone = IconTone.Gold)
                VerticalSpacerD400()
                Text(
                    text = formatThousands(pack.grantsChips),
                    // H700 (vs H800) so the widest amount ("120,000") fits a
                    // narrow ~100dp column without wrapping in the 3-up layout.
                    typography = AppTheme.typography.Heading.H700,
                    color = AppTheme.colors.content,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                VerticalSpacerD400()
                PricePill(text = pack.store.fallbackPriceDisplay)
            }
        }
    }
    val packBadge = pack.badge
    if (isPopular && packBadge != null) {
        BadgedBox(
            modifier = modifier.fillMaxWidth(),
            contentRadius = Radii.Card,
            placement = BadgePlacement.EdgeAlignedTop,
            badge = { OverhangBadge(text = packBadge, accent = AppTheme.colors.accentPrimary) },
            content = { inner() },
        )
    } else {
        Box(modifier = modifier.fillMaxWidth()) { inner() }
    }
}

/** Full-width horizontal tier card for the narrow (stacked) layout. */
@Composable
private fun ChipTierRowCard(
    pack: Product.ChipPack,
    isPopular: Boolean,
    onClick: () -> Unit,
) {
    val gradient = chipPackGradient()
    val inner: @Composable () -> Unit = {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .thenIf(isPopular) {
                    border(2.dp, AppTheme.colors.accentPrimary.color, Radii.Card.shape)
                },
            color = AppTheme.colors.surface,
            contentColor = AppTheme.colors.content,
            radius = Radii.Card,
            elevation = Elevation.Card,
            onClick = onClick,
            bounceScale = 0.97f,
            contentPadding = PaddingValues(0.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(gradient)
                    .padding(Dimension.D600),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProductIcon(emoji = pack.iconEmoji, tone = IconTone.Gold)
                Spacer(modifier = Modifier.size(Dimension.D500))
                Text(
                    text = formatThousands(pack.grantsChips),
                    typography = AppTheme.typography.Heading.H700,
                    color = AppTheme.colors.content,
                )
                Spacer(modifier = Modifier.weight(1f))
                PricePill(text = pack.store.fallbackPriceDisplay)
            }
        }
    }
    val packBadge = pack.badge
    if (isPopular && packBadge != null) {
        BadgedBox(
            modifier = Modifier.fillMaxWidth(),
            contentRadius = Radii.Card,
            placement = BadgePlacement.EdgeAlignedTop,
            badge = { OverhangBadge(text = packBadge, accent = AppTheme.colors.accentPrimary) },
            content = { inner() },
        )
    } else {
        inner()
    }
}

// ---------------------------------------------------------------------------
// Chip offer card (grid item) — owned / affordable / dimmed
// ---------------------------------------------------------------------------

/**
 * Product-icon edge on a specialty (cosmetic) offer card. A touch smaller than
 * the chip-pack tiles so the two-column specialty grid reads as congruent
 * rather than icon-heavy (SHOP-7).
 */
private val SpecialtyIconSize = 56.dp

/**
 * Chip-offer grid card. Visual treatment dispatches on [cardState]:
 *
 *  - [ChipOfferCardState.Available] → normal card, ChipCostFooter
 *  - [ChipOfferCardState.Insufficient] → dimmed card, cost footer in
 *    danger color + "X more" deficit caption
 *  - [ChipOfferCardState.Locked] → dimmed card, lock-icon overlay on the
 *    product icon, "Unlocks at Level N" footer (no chip cost shown)
 *  - [ChipOfferCardState.Owned] → normal card, green check on the icon,
 *    "OWNED" footer
 *
 * Only [ChipOfferCardState.Available] is tappable. Other states call back
 * into [onClick] for analytics / future "tap to learn more" interactions
 * but the VM's [ShopAction.RequestPurchase] is gated as well.
 */
@Composable
private fun ChipOfferCard(
    offer: Product.ChipOffer,
    cardState: ChipOfferCardState,
    tier: CosmeticTier?,
    timeAnchor: com.dangerfield.cards.libraries.products.CatalogTimeAnchor?,
    onExpired: () -> Unit,
    onClick: () -> Unit,
) {
    // Dimming model: in Locked / Insufficient states we want the card to
    // read as "blocked," but the BLOCKING INFO (lock icon, "Unlocks at
    // level N", "Need X more chips") must stay crisp — that's where the
    // user's attention should land. So we DON'T put alpha on the Surface
    // itself; instead the dimmable elements (icon, title, subtitle) carry
    // their own alpha, while the state overlays + footers render at full
    // opacity on top.
    val dimmableAlpha = when (cardState) {
        is ChipOfferCardState.Locked -> 0.45f
        is ChipOfferCardState.Insufficient -> 0.55f
        is ChipOfferCardState.Available -> 1f
        is ChipOfferCardState.Owned -> 1f
    }
    // Every state can open the sheet now — see the VM. Non-Available
    // sheets show "here's what this is" content with a disabled CTA.
    val card: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            color = AppTheme.colors.surface,
            contentColor = AppTheme.colors.content,
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
                Box(contentAlignment = Alignment.Center) {
                    if (hasCosmeticPreview(offer.id)) {
                        CosmeticPreview(
                            productId = offer.id,
                            emoji = offer.iconEmoji,
                            size = SpecialtyIconSize,
                            modifier = Modifier.alpha(dimmableAlpha),
                        )
                    } else {
                        ProductIcon(
                            emoji = offer.iconEmoji,
                            tone = IconTone.Accent,
                            size = SpecialtyIconSize,
                            modifier = Modifier.alpha(dimmableAlpha),
                        )
                    }
                    when (cardState) {
                        is ChipOfferCardState.Owned -> OwnedCheck(
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                        // Lock overlay renders OUTSIDE the alpha so it
                        // pops over the dimmed icon, not through it.
                        is ChipOfferCardState.Locked -> LockIconOverlay()
                        else -> Unit
                    }
                }
                VerticalSpacerD400()
                Text(
                    text = offer.title,
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.content,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alpha(dimmableAlpha),
                )
                VerticalSpacerD100()
                Text(
                    text = offer.subtitle,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.contentSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alpha(dimmableAlpha),
                )
                if (isPersonalCosmetic(offer.id)) {
                    // "Only you see this" — heads off the V1 paper cut
                    // where a player buys a felt expecting other seats
                    // at the table to see it. Stays dimmed alongside
                    // the subtitle since it reads as auxiliary metadata
                    // about the product, not state of the purchase.
                    VerticalSpacerD100()
                    Text(
                        text = stringResource(Res.string.shop_personal_cosmetic_hint),
                        typography = AppTheme.typography.Label.L300,
                        color = AppTheme.colors.contentSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.alpha(dimmableAlpha),
                    )
                }
                if (tier == CosmeticTier.EARN_OR_BUY) {
                    VerticalSpacerD100()
                    Text(
                        text = "🏆 " + stringResource(Res.string.shop_also_earnable_hint),
                        typography = AppTheme.typography.Label.L300,
                        color = AppTheme.colors.league.amethyst,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.alpha(dimmableAlpha),
                    )
                }
                // Push the footer to the bottom edge so every card in the
                // row aligns regardless of how much content sits above.
                Spacer(modifier = Modifier.weight(1f, fill = true))
                Spacer(modifier = Modifier.height(Dimension.D400))
                // Footers render at full opacity regardless of card state
                // — they carry the actionable info the user needs to see.
                when (cardState) {
                    is ChipOfferCardState.Available -> ChipCostFooter(
                        cost = cardState.costChips,
                        canAfford = true,
                    )
                    is ChipOfferCardState.Insufficient -> InsufficientChipsFooter(
                        cost = cardState.costChips,
                        shortBy = cardState.shortBy,
                    )
                    is ChipOfferCardState.Locked -> LockedFooter(
                        requiredLevel = cardState.requiredLevel,
                    )
                    is ChipOfferCardState.Owned -> OwnedFooter()
                }
            }
        }
    }

    // Owned + Locked items skip ALL corner badges — the icon-level
    // overlay already says "this card is in a special state."
    //
    // Otherwise the priority is countdown (sale window) > marketing badge.
    val showAnyBadge = cardState !is ChipOfferCardState.Owned &&
        cardState !is ChipOfferCardState.Locked
    val saleEpochMs = offer.availableUntilEpochMs
    val offerBadge = offer.badge
    val badgeContent: @Composable (() -> Unit)? = when {
        !showAnyBadge -> null
        saleEpochMs != null && timeAnchor != null -> {
            {
                CountdownBadge(
                    timeAnchor = timeAnchor,
                    availableUntilEpochMs = saleEpochMs,
                    onExpired = onExpired,
                )
            }
        }
        offerBadge != null -> {
            { OverhangBadge(text = offerBadge, accent = AppTheme.colors.danger) }
        }
        else -> null
    }
    if (badgeContent == null) {
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) { card() }
    } else {
        BadgedBox(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            contentRadius = Radii.Card,
            placement = BadgePlacement.EdgeAlignedTop,
            badge = { badgeContent() },
            content = { card() },
        )
    }
}

/**
 * White lock icon centered on the product icon — the most direct visual
 * cue that a card is gated. Sits in a fully-opaque dark bubble so it
 * reads cleanly OVER the dimmed icon underneath (don't inherit the
 * dimmable alpha; the lock is the critical info).
 */
@Composable
private fun LockIconOverlay() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(AppTheme.colors.background.color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "🔒",
            typography = AppTheme.typography.Heading.H600,
            color = AppTheme.colors.content,
        )
    }
}

/**
 * "Unlocks at Level N" pill. Brighter copy + heavier weight than a
 * regular metadata footer so it stands out on the dimmed card.
 */
@Composable
private fun LockedFooter(requiredLevel: Int) {
    Box(
        modifier = Modifier
            .clip(Radii.Round.shape)
            .background(AppTheme.colors.surfaceHigh.color)
            .padding(horizontal = Dimension.D500, vertical = Dimension.D200),
    ) {
        Text(
            text = stringResource(Res.string.shop_unlocks_at_level, requiredLevel),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.content,
        )
    }
}

/**
 * Cost footer for the "can't afford" state. The deficit copy "Need
 * X more chips" sits in the danger color at body weight so it stays
 * legible on top of the dimmed card content above — it's the most
 * important thing on the card in this state.
 */
@Composable
private fun InsufficientChipsFooter(cost: Long, shortBy: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .clip(Radii.Round.shape)
                .background(AppTheme.colors.danger.color.copy(alpha = 0.18f))
                .padding(horizontal = Dimension.D500, vertical = Dimension.D200),
        ) {
            // ChipCoinAmount keeps the gold-coin + count shape aligned with
            // every other cost / balance surface; danger color carries the
            // can't-afford signal.
            ChipCoinAmount(
                amount = cost,
                coinSize = 16.dp,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.danger,
                formatter = ::formatChips,
            )
        }
        if (shortBy > 0) {
            VerticalSpacerD100()
            Text(
                text = stringResource(Res.string.shop_need_chips_more, formatChips(shortBy)),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.danger,
            )
        }
    }
}

@Composable
private fun OwnedCheck(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(AppTheme.colors.success.color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "✓",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.content,
        )
    }
}

@Composable
private fun OwnedFooter() {
    Box(
        modifier = Modifier
            .clip(Radii.Round.shape)
            .background(AppTheme.colors.success.color.copy(alpha = 0.18f))
            .padding(horizontal = Dimension.D500, vertical = Dimension.D200),
    ) {
        Text(
            text = stringResource(Res.string.shop_owned_badge),
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.success,
        )
    }
}

@Composable
private fun ChipCostFooter(cost: Long, canAfford: Boolean) {
    val bg = if (canAfford) AppTheme.colors.surfaceHigh.color else AppTheme.colors.surfaceDisabled.color
    Box(
        modifier = Modifier
            .clip(Radii.Round.shape)
            .background(bg)
            .padding(horizontal = Dimension.D500, vertical = Dimension.D200),
    ) {
        // ChipCoinAmount keeps the gold-coin + count shape aligned with
        // every other cost / balance surface (table pot, stack, header).
        ChipCoinAmount(
            amount = cost,
            coinSize = 16.dp,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.content,
            formatter = ::formatChips,
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
                .background(AppTheme.colors.surfaceRaised.color),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "🛍️",
                typography = AppTheme.typography.Heading.H1100,
                color = AppTheme.colors.content,
            )
        }
        VerticalSpacerD700()
        Text(
            text = stringResource(Res.string.shop_empty_title),
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.content,
        )
        VerticalSpacerD200()
        Text(
            text = stringResource(Res.string.shop_empty_subtitle),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
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
        contentColor = AppTheme.colors.content,
        radius = Radii.Card,
        elevation = Elevation.Card,
        onClick = onDismiss,
        bounceScale = 1f,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.shop_error_title),
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.content,
                )
                VerticalSpacerD100()
                Text(
                    text = message,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.content,
                    modifier = Modifier.alpha(0.85f),
                )
            }
            Spacer(modifier = Modifier.size(Dimension.D300))
            ButtonPrimary(onClick = onRetry, size = ButtonSize.Small) {
                Text(text = stringResource(Res.string.shop_error_retry))
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
    PreviewContent(bottomBar = PreviewBottomBar.Shop) {
        ShopScreen(
            state = ShopState(isRefreshing = true, hasLoaded = false, chipBalance = 12_450),
            onAction = {},
            onProductTap = {},
            onIdeaTap = {},
        )
    }
}

@Preview
@Composable
private fun ShopScreenPreview_Empty() {
    PreviewContent(bottomBar = PreviewBottomBar.Shop) {
        ShopScreen(
            state = ShopState(hasLoaded = true, chipBalance = 12_450),
            onAction = {},
            onProductTap = {},
            onIdeaTap = {},
        )
    }
}

@Preview
@Composable
private fun ShopScreenPreview_FullCatalog() {
    PreviewContent(bottomBar = PreviewBottomBar.Shop) {
        ShopScreen(
            state = ShopState(
                hasLoaded = true,
                chipBalance = 12_450,
                catalog = previewFullCatalog(),
            ),
            onAction = {},
            onProductTap = {},
            onIdeaTap = {},
        )
    }
}

@Preview(widthDp = 800, heightDp = 380)
@Composable
private fun ShopScreenPreview_Landscape() {
    // Landscape lens on the full catalog — surfaces how the product grid
    // and chip header reflow on a wide canvas before any layout tuning.
    PreviewContent(bottomBar = PreviewBottomBar.Shop) {
        ShopScreen(
            state = ShopState(
                hasLoaded = true,
                chipBalance = 12_450,
                catalog = previewFullCatalog(),
            ),
            onAction = {},
            onProductTap = {},
            onIdeaTap = {},
        )
    }
}

@Preview
@Composable
private fun ShopScreenPreview_MixedOwnedAndDisabled() {
    val catalog = previewFullCatalog()
    PreviewContent(bottomBar = PreviewBottomBar.Shop) {
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
            onProductTap = {},
            onIdeaTap = {},
        )
    }
}

@Preview
@Composable
private fun ChipOfferCardPreview_EarnOrBuyTier() {
    PreviewContent {
        Box(modifier = Modifier.padding(16.dp)) {
            ChipOfferCard(
                offer = Product.ChipOffer(
                    id = "tool_win_odds",
                    title = "Win Odds Display",
                    subtitle = "Utility",
                    iconEmoji = "📊",
                    costChips = 10_000,
                    grantsKey = "tool.win_odds",
                    unlockLevel = 1,
                ),
                cardState = ChipOfferCardState.Available(costChips = 10_000),
                tier = CosmeticTier.EARN_OR_BUY,
                timeAnchor = null,
                onExpired = {},
                onClick = {},
            )
        }
    }
}

@Preview
@Composable
private fun ShopScreenPreview_ErrorWithPriorCatalog() {
    PreviewContent(bottomBar = PreviewBottomBar.Shop) {
        ShopScreen(
            state = ShopState(
                hasLoaded = true,
                chipBalance = 12_450,
                catalog = previewFullCatalog(),
                hasRefreshError = true,
            ),
            onAction = {},
            onProductTap = {},
            onIdeaTap = {},
        )
    }
}

private fun previewFullCatalog(): ProductCatalog = ProductCatalog(
    chipPacks = listOf(
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
            badge = "POPULAR",
            grantsChips = 30_000,
            store = StoreSku("chips_medium", "$4.99"),
        ),
        Product.ChipPack(
            id = "chip_pack_large",
            title = "Whale Stack",
            subtitle = "120,000 chips",
            iconEmoji = "🐋",
            grantsChips = 120_000,
            store = StoreSku("chips_large", "$14.99"),
        ),
    ),
    chipOffers = listOf(
        Product.ChipOffer(
            id = "boost_xp_2x",
            title = "XP Boost",
            subtitle = "5 minutes",
            description = "Earn double XP on every hand you play for 5 minutes. Save them up and activate when you're ready.",
            iconEmoji = "⚡",
            costChips = 1_000,
            grantsKey = "boost.xp_2x",
            unlockLevel = 1,
        ),
        Product.ChipOffer(
            id = "emotes_drama",
            title = "Drama Emote Pack",
            subtitle = "Emotes · 4 reactions",
            description = "Unlocks 💃 🧂 🎭 🤦 — screen-filling reactions for the table. Equip individually from your items.",
            iconEmoji = "💃",
            costChips = 3_500,
            grantsKey = "emotes.drama",
            unlockLevel = 1,
        ),
        Product.ChipOffer(
            id = "felt_royal_red",
            title = "Royal Red Felt",
            subtitle = "Table felt",
            iconEmoji = "🟥",
            costChips = 1_500,
            grantsKey = "felt.royal_red",
            unlockLevel = 1,
        ),
        Product.ChipOffer(
            id = "cardback_marble",
            title = "Marble Card Back",
            subtitle = "Card back",
            iconEmoji = "🂠",
            badge = "POPULAR",
            costChips = 4_000,
            grantsKey = "cardback.marble",
            unlockLevel = 3,
        ),
        Product.ChipOffer(
            id = "tool_win_odds",
            title = "Win Odds Display",
            subtitle = "Utility",
            description = "Live win-% during a hand, computed from your hole cards + the visible board.",
            iconEmoji = "📊",
            costChips = 10_000,
            grantsKey = "tool.win_odds",
            unlockLevel = 10,
        ),
        Product.ChipOffer(
            id = "title_high_roller",
            title = "High Roller",
            subtitle = "Player title",
            iconEmoji = "🏆",
            badge = "RARE",
            costChips = 25_000,
            grantsKey = "title.high_roller",
            unlockLevel = 20,
        ),
    ),
)
