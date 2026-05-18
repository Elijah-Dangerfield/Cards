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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * Square rounded-rect tile with a placeholder emoji at center. The size /
 * radius shrinks on the grid and grows in the sheet via [size] and
 * [cornerRadius] so callers don't have to re-derive proportions per
 * surface.
 *
 * Once real drawable assets ship via `Res.allDrawableResources[iconKey]`,
 * the emoji placeholder gets swapped for an Image; the API stays the same.
 */
@Composable
internal fun ProductIcon(
    iconKey: String,
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
            text = emojiForIconKey(iconKey),
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
 * version used as a [BadgedBox] overhang on grid cards so it reads as
 * "stuck on" the card.
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

/**
 * Asset-key → emoji placeholder. Real drawable resources land via the
 * `Res.allDrawableResources[iconKey]` lookup; this fallback keeps the UI
 * alive and on-brand until the design files are wired in.
 */
internal fun emojiForIconKey(iconKey: String): String = when {
    iconKey.startsWith("chips_") -> when (iconKey) {
        "chips_small" -> "🪙"
        "chips_medium" -> "💰"
        "chips_large" -> "💎"
        "chips_mega" -> "👑"
        else -> "🪙"
    }
    iconKey.startsWith("emote_") -> when (iconKey) {
        "emote_dance" -> "💃"
        "emote_tilt" -> "🧂"
        "emote_think" -> "🤔"
        "emote_facepalm" -> "🤦"
        else -> "😀"
    }
    iconKey.startsWith("cardback_") -> "🂠"
    iconKey.startsWith("table_") -> "🎰"
    iconKey.startsWith("title_") -> "🏆"
    else -> "🎁"
}
