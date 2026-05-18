package com.dangerfield.cards.features.shop.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.ui.components.dialog.BubbleSurface
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme

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
    cornerRadius: Dp = 20.dp,
    modifier: Modifier = Modifier,
) {
    val bg = when (tone) {
        IconTone.Gold -> ColorResource.Amber600.color.copy(alpha = 0.18f)
        IconTone.Accent -> AppTheme.colors.accentPrimary.color.copy(alpha = 0.18f)
        IconTone.Neutral -> AppTheme.colors.surfaceSecondary.color
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.text,
        )
    }
}

/**
 * Soft pill — translucent tinted background, accent-colored text. Used
 * inline (under a title, in the sheet) when we want to label something
 * with a colored marker that doesn't scream "buy this now" the way the
 * solid grid badge does.
 */
@Composable
internal fun BadgePill(text: String, accent: ColorResource) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accent.color.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            typography = AppTheme.typography.Label.L400,
            color = accent,
        )
    }
}

/**
 * Solid pill — accent-colored background, background-colored text. The
 * version used as a [com.dangerfield.cards.libraries.ui.components.BadgedBox]
 * overhang on grid cards so it reads as "stuck on" the card.
 */
@Composable
internal fun OverhangBadge(text: String, accent: ColorResource) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accent.color)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.background,
        )
    }
}

/**
 * Bubble surface for the purchase sheet's emoji handle, picked so the
 * sheet's bubble matches the visual treatment of the product's grid card.
 * Three branches mirror the three card looks:
 *
 *  - Featured chip pack → the gradient backdrop from [FeaturedPackHero].
 *    Keeps the visual line continuous from tap → sheet.
 *  - Regular chip pack → gold tint (matches the [IconTone.Gold] tile).
 *  - Chip-purchasable offer → accent tint (matches [IconTone.Accent]).
 *
 * Tint alpha matches [ProductIcon] so the bubble reads as the same chip
 * the user just tapped, not a fresh visual.
 */
@Composable
internal fun productBubbleSurface(product: Product): BubbleSurface = when (product) {
    is Product.ChipPack -> if (product.featured) {
        BubbleSurface.Gradient(
            Brush.linearGradient(
                colors = listOf(
                    AppTheme.colors.accentSecondary.color,
                    AppTheme.colors.accentPrimary.color,
                ),
            ),
        )
    } else {
        BubbleSurface.Solid(color = ColorResource.Amber600, alpha = 0.18f)
    }
    is Product.ChipOffer -> BubbleSurface.Solid(
        color = AppTheme.colors.accentPrimary,
        alpha = 0.18f,
    )
}

/** Format chip costs with a thousands separator (KMP — no Locale ergonomics). */
internal fun formatChips(amount: Long): String {
    if (amount < 1_000) return amount.toString()
    val s = amount.toString()
    val out = StringBuilder()
    var count = 0
    for (i in s.lastIndex downTo 0) {
        out.append(s[i])
        count++
        if (count == 3 && i > 0) {
            out.append(',')
            count = 0
        }
    }
    return out.reverse().toString()
}
