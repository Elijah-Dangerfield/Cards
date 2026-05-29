package com.dangerfield.cards.features.shop.impl

import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.shop_empty_subtitle
import cards.libraries.resources.generated.resources.shop_empty_title
import cards.libraries.resources.generated.resources.shop_error_retry
import cards.libraries.resources.generated.resources.shop_error_title
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.PurchaseState
import com.dangerfield.cards.libraries.cards.isPersonalCosmetic
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
import com.dangerfield.cards.libraries.ui.components.BalancePillSlot
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

            state.errorMessage?.let { message ->
                ErrorBanner(
                    message = message,
                    onRetry = { onAction(ShopAction.Refresh(force = true)) },
                    onDismiss = { onAction(ShopAction.DismissError) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
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
    val featured = state.catalog.chipPacks.firstOrNull { it.featured }
    val otherPacks = state.catalog.chipPacks.filterNot { it.id == featured?.id }

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
        ShopHeader(chips = state.chipBalance ?: 0L)
        VerticalSpacerD700()

        featured?.let {
            FeaturedPackHero(
                pack = it,
                onClick = { onProductTap(it.id) },
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
                    timeAnchor = state.timeAnchor,
                    onExpired = { onAction(ShopAction.Refresh(force = true)) },
                    onClick = { onProductTap(pack.id) },
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
                    cardState = state.classify(offer),
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
private fun ShopHeader(chips: Long) {
    BalancePillSlot(chips = chips) {
        Column {
            Text(
                text = stringResource(Res.string.shop_header_title),
                typography = AppTheme.typography.Heading.H1000,
                color = AppTheme.colors.text,
            )
            VerticalSpacerD100()
            Text(
                text = stringResource(Res.string.shop_header_subtitle),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.textSecondary,
            )
        }
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
                typography = AppTheme.typography.Body.B500,
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
                .padding(horizontal = Dimension.D850, vertical = Dimension.D850),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeroProductIcon(emoji = pack.iconEmoji)
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
private fun HeroProductIcon(emoji: String) {
    Box(
        modifier = Modifier
            .size(86.dp)
            .clip(Radii.R850.shape)
            .background(AppTheme.colors.background.color.copy(alpha = 0.18f))
            .border(
                width = 2.dp,
                color = AppTheme.colors.onAccentPrimary.color.copy(alpha = 0.25f),
                shape = Radii.R850.shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
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
private fun ChipPackCard(
    pack: Product.ChipPack,
    timeAnchor: com.dangerfield.cards.libraries.products.CatalogTimeAnchor?,
    onExpired: () -> Unit,
    onClick: () -> Unit,
) {
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
                ProductIcon(emoji = pack.iconEmoji, tone = IconTone.Gold)
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

    // Badge slot priority:
    //   1. Sale-window countdown — most urgent info, beats marketing badges.
    //   2. Marketing badge (e.g. "BEST VALUE").
    //   3. No badge.
    val saleEpochMs = pack.availableUntilEpochMs
    val packBadge = pack.badge
    val badgeContent: @Composable (() -> Unit)? = when {
        saleEpochMs != null && timeAnchor != null -> {
            {
                CountdownBadge(
                    timeAnchor = timeAnchor,
                    availableUntilEpochMs = saleEpochMs,
                    onExpired = onExpired,
                )
            }
        }
        packBadge != null -> {
            { OverhangBadge(text = packBadge, accent = ColorResource.Amber600) }
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

// ---------------------------------------------------------------------------
// Chip offer card (grid item) — owned / affordable / dimmed
// ---------------------------------------------------------------------------

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
                Box(contentAlignment = Alignment.Center) {
                    if (hasCosmeticPreview(offer.id)) {
                        CosmeticPreview(
                            productId = offer.id,
                            emoji = offer.iconEmoji,
                            size = 64.dp,
                            modifier = Modifier.alpha(dimmableAlpha),
                        )
                    } else {
                        ProductIcon(
                            emoji = offer.iconEmoji,
                            tone = IconTone.Accent,
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
                    color = AppTheme.colors.text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alpha(dimmableAlpha),
                )
                VerticalSpacerD100()
                Text(
                    text = offer.subtitle,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
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
                        color = AppTheme.colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.alpha(dimmableAlpha),
                    )
                }
                // Push the footer to the bottom edge — see ChipPackCard
                // for the rationale.
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
            { OverhangBadge(text = offerBadge, accent = ColorResource.Red400) }
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
            color = AppTheme.colors.text,
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
            .background(AppTheme.colors.surfaceTertiary.color)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = stringResource(Res.string.shop_unlocks_at_level, requiredLevel),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
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
                .padding(horizontal = 12.dp, vertical = 6.dp),
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
private fun OwnedFooter() {
    Box(
        modifier = Modifier
            .clip(Radii.Round.shape)
            .background(AppTheme.colors.status.okay.color.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = stringResource(Res.string.shop_owned_badge),
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.status.okay,
        )
    }
}

@Composable
private fun ChipCostFooter(cost: Long, canAfford: Boolean) {
    val bg = if (canAfford) AppTheme.colors.surfaceTertiary.color else AppTheme.colors.surfaceDisabled.color
    Box(
        modifier = Modifier
            .clip(Radii.Round.shape)
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // ChipCoinAmount keeps the gold-coin + count shape aligned with
        // every other cost / balance surface (table pot, stack, header).
        ChipCoinAmount(
            amount = cost,
            coinSize = 16.dp,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
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
                .background(AppTheme.colors.surfaceSecondary.color),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "🛍️",
                typography = AppTheme.typography.Heading.H1100,
                color = AppTheme.colors.text,
            )
        }
        VerticalSpacerD700()
        Text(
            text = stringResource(Res.string.shop_empty_title),
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.text,
        )
        VerticalSpacerD200()
        Text(
            text = stringResource(Res.string.shop_empty_subtitle),
            typography = AppTheme.typography.Body.B500,
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
                    text = stringResource(Res.string.shop_error_title),
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
private fun ShopScreenPreview_ErrorWithPriorCatalog() {
    PreviewContent(bottomBar = PreviewBottomBar.Shop) {
        ShopScreen(
            state = ShopState(
                hasLoaded = true,
                chipBalance = 12_450,
                catalog = previewFullCatalog(),
                errorMessage = "Offline — pulled the cached shop.",
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
            id = "chip_pack_medium",
            title = "Tall Stack",
            subtitle = "30,000 chips",
            iconEmoji = "💰",
            featured = true,
            badge = "BEST VALUE",
            grantsChips = 30_000,
            store = StoreSku("chips_medium", "$4.99"),
        ),
        Product.ChipPack(
            id = "chip_pack_small",
            title = "Pocket Stack",
            subtitle = "5,000 chips",
            iconEmoji = "🪙",
            grantsChips = 5_000,
            store = StoreSku("chips_small", "$0.99"),
        ),
        Product.ChipPack(
            id = "chip_pack_large",
            title = "Whale Stack",
            subtitle = "80,000 chips",
            iconEmoji = "🐋",
            badge = "+20%",
            grantsChips = 80_000,
            store = StoreSku("chips_large", "$9.99"),
        ),
        Product.ChipPack(
            id = "chip_pack_mega",
            title = "High Roller",
            subtitle = "250,000 chips",
            iconEmoji = "👑",
            grantsChips = 250_000,
            store = StoreSku("chips_mega", "$19.99"),
        ),
    ),
    chipOffers = listOf(
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
            id = "table_neon",
            title = "Neon Table",
            subtitle = "Table theme",
            iconEmoji = "🎰",
            featured = true,
            badge = "NEW",
            costChips = 8_000,
            grantsKey = "table.neon",
            unlockLevel = 8,
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
