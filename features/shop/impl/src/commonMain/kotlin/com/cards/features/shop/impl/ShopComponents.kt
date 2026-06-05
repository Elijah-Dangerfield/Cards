package com.dangerfield.cards.features.shop.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.CosmeticSlot
import com.dangerfield.cards.libraries.cards.cosmeticSlotFor
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.ui.components.StatusPill
import com.dangerfield.cards.libraries.ui.components.dialog.BubbleSurface
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.Radius
import com.dangerfield.cards.system.clip

/**
 * Shared building blocks used by [ShopScreen] grid cells and
 * [PurchaseConfirmSheet]. Pulled here so the sheet file doesn't need to
 * re-define the same icon-tile and pill primitives.
 *
 * Nothing in here is a candidate for the design system yet — these are
 * shop-specific compositions of DS primitives. If a sibling feature ends
 * up wanting them, we'd move them up at that point.
 */

/**
 * Tile background tone for a product icon. Three options that match the
 * three "kinds" of thing in the shop:
 *  - [Gold] — chip packs (chips are gold across the app)
 *  - [Accent] — chip-purchasable items (table themes, emotes, etc.)
 *  - [Neutral] — anything that doesn't need to scream (used by the
 *    cannot-afford / pending state)
 */
internal enum class IconTone { Gold, Accent, Neutral }

/**
 * Square rounded-rect tile with the product's emoji at center.
 *
 * The emoji is the server-authoritative product visual — there's no
 * client-side iconKey → emoji mapping. When real drawable assets ship,
 * this composable swaps the emoji Text for an Image and the call sites
 * don't change.
 */
@Composable
internal fun ProductIcon(
    emoji: String,
    tone: IconTone,
    size: Dp = 64.dp,
    radius: Radius = Radii.R800,
    modifier: Modifier = Modifier,
) {
    val bg = when (tone) {
        IconTone.Gold -> AppTheme.colors.accentPrimary.color.copy(alpha = 0.18f)
        IconTone.Accent -> AppTheme.colors.accentPrimary.color.copy(alpha = 0.18f)
        IconTone.Neutral -> AppTheme.colors.surfaceRaised.color
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(radius)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.content,
        )
    }
}

/**
 * Soft pill — translucent tinted background, accent-colored text. Used
 * inline (under a title, in the sheet) when we want to label something
 * with a colored marker that doesn't scream "buy this now" the way the
 * solid grid badge does.
 *
 * Thin shop facade over [StatusPill] — pins the translucent-flavor
 * bg/fg pair plus the shop's Label.L400 + 10/4 padding shape so the
 * five callsites stay readable as "soft accent pill".
 */
@Composable
internal fun BadgePill(text: String, accent: ColorResource) {
    StatusPill(
        text = text,
        background = accent.withAlpha(SOFT_PILL_ALPHA),
        foreground = accent,
        typography = AppTheme.typography.Label.L400,
        contentPadding = SHOP_PILL_PADDING,
    )
}

/**
 * Solid pill — accent-colored background, background-colored text. The
 * version used as a [com.dangerfield.cards.libraries.ui.components.BadgedBox]
 * overhang on grid cards so it reads as "stuck on" the card.
 *
 * Thin shop facade over [StatusPill] — pins the solid-flavor bg/fg
 * pair plus the shop's Label.L400 + 10/4 padding shape.
 */
@Composable
internal fun OverhangBadge(text: String, accent: ColorResource) {
    StatusPill(
        text = text,
        background = accent,
        foreground = AppTheme.colors.background,
        typography = AppTheme.typography.Label.L400,
        contentPadding = SHOP_PILL_PADDING,
    )
}

private const val SOFT_PILL_ALPHA = 0.18f
private val SHOP_PILL_PADDING = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

/**
 * Bubble surface for the purchase sheet's emoji handle, picked so the
 * sheet's bubble matches the visual treatment of the product's grid card.
 * Three branches mirror the three card looks:
 *
 *  - Chip pack → the accent gradient backdrop (every chip tier wears it now,
 *    not just the featured one, matching the gradient chip cards in the grid).
 *    Keeps the visual line continuous from tap → sheet.
 *  - Chip-purchasable offer → accent tint (matches [IconTone.Accent]).
 *
 * Tint alpha matches [ProductIcon] so the bubble reads as the same chip
 * the user just tapped, not a fresh visual.
 */
@Composable
internal fun productBubbleSurface(product: Product): BubbleSurface = when (product) {
    is Product.ChipPack -> BubbleSurface.Gradient(
        Brush.linearGradient(
            colors = listOf(
                AppTheme.colors.accentSecondary.color,
                AppTheme.colors.accentPrimary.color,
            ),
        ),
    )
    is Product.ChipOffer -> BubbleSurface.Solid(
        color = AppTheme.colors.accentPrimary,
        alpha = 0.18f,
    )
}

/**
 * True for product ids that resolve to a real visual swatch via
 * `CosmeticPreview` — felts and card backs today. Titles are cosmetic
 * but have no visual preview shape, so they keep the toned emoji tile.
 * Centralised so the shop grid + purchase sheet stay in sync on which
 * products get the swatch treatment.
 */
internal fun hasCosmeticPreview(productId: String): Boolean =
    cosmeticSlotFor(productId).let { it == CosmeticSlot.Felt || it == CosmeticSlot.CardBack }

/**
 * Re-export of the shared thousands-separator formatter so the shop
 * module's existing callsites keep their familiar `formatChips(...)`
 * import path. The canonical implementation lives in
 * [com.dangerfield.cards.libraries.cards.formatThousands].
 */
internal fun formatChips(amount: Long): String =
    com.dangerfield.cards.libraries.cards.formatThousands(amount)

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun ProductIconPreview_AllTones() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            ProductIcon(emoji = "💰", tone = IconTone.Gold)
            ProductIcon(emoji = "🎭", tone = IconTone.Accent)
            ProductIcon(emoji = "🪙", tone = IconTone.Neutral)
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun BadgePillPreview() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            BadgePill(text = "NEW", accent = AppTheme.colors.accentPrimary)
            BadgePill(text = "+20% BONUS", accent = AppTheme.colors.accentPrimary)
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun OverhangBadgePreview() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            OverhangBadge(text = "BEST VALUE", accent = AppTheme.colors.accentPrimary)
            OverhangBadge(text = "LIMITED", accent = AppTheme.colors.accentPrimary)
        }
    }
}
