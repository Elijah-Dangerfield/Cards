package com.dangerfield.cards.libraries.ui.components.poker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.CosmeticSlot
import com.dangerfield.cards.libraries.cards.cosmeticSlotFor
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Visual swatch for a catalog product. Resolves the [productId] to the
 * right cosmetic-shaped preview so screens that list owned / for-sale
 * items (My Items, shop detail) can show "what does this look like?"
 * rather than just an emoji + name.
 *
 * Three kinds of resolution:
 *  - **Felt** — paints a tinted square with the equipped felt color via
 *    [feltSurfaceColor]. The fallback `Default` felt resolves to the app
 *    background, so unrecognized "felt-shaped" ids still render.
 *  - **Card back** — renders a small [PlayingCardBack] in the resolved
 *    style. The card's shadow + border + brush all come from the DS,
 *    matching what shows up at the table.
 *  - **Pack** — when [packEmojis] holds 2+ glyphs (an avatar or emote
 *    pack whose contents are known), renders a [CosmeticPackThumbnail]:
 *    two overlapping emoji tiles that read as a "pack" rather than a lone
 *    glyph.
 *  - **Emoji fallback** — for everything else (utilities, or a pack whose
 *    contents we don't have), draws the product's [emoji] inside a circle
 *    so the row never looks empty.
 *
 * The component is *intentionally* a chooser, not a sealed type — the
 * single decision point keeps callsites tight (`CosmeticPreview(productId
 * = item.productId, emoji = item.iconEmoji, size = 48.dp)`) and centralises
 * the "is this a felt? a card back? something else?" classification so
 * server-side catalog additions only need updates in
 * [feltForProductId] / [cardBackForProductId].
 */
@Composable
fun CosmeticPreview(
    productId: String,
    emoji: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    packEmojis: List<String> = emptyList(),
) {
    when (cosmeticSlotFor(productId)) {
        CosmeticSlot.Felt -> FeltSwatch(productId = productId, size = size, modifier = modifier)
        CosmeticSlot.CardBack -> CardBackPreview(productId = productId, size = size, modifier = modifier)
        else -> if (packEmojis.size >= 2) {
            CosmeticPackThumbnail(emojis = packEmojis, size = size, modifier = modifier)
        } else {
            EmojiTile(emoji = emoji, size = size, modifier = modifier)
        }
    }
}

/**
 * A "pack" swatch: two of the pack's emoji tiles stacked with a slight
 * offset so a bundle (avatar pack, emote pack) reads as a stack of things
 * rather than a single glyph. The front tile carries a background-colored
 * ring so it stays legible where it overlaps the back tile.
 *
 * Falls back to a single tile if fewer than two emojis are supplied.
 */
@Composable
fun CosmeticPackThumbnail(
    emojis: List<String>,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val front = emojis.firstOrNull() ?: "🎁"
    val back = emojis.getOrNull(1)
    if (back == null) {
        EmojiTile(emoji = front, size = size, modifier = modifier)
        return
    }
    // Each tile is a bit smaller than the footprint so the two fit on the
    // diagonal with a visible offset.
    val tile = size * 0.72f
    Box(modifier = modifier.size(size)) {
        EmojiTile(
            emoji = back,
            size = tile,
            modifier = Modifier
                .align(Alignment.TopStart)
                .alpha(0.75f),
        )
        EmojiTile(
            emoji = front,
            size = tile,
            ringColor = AppTheme.colors.background.color,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

@Composable
private fun FeltSwatch(productId: String, size: Dp, modifier: Modifier = Modifier) {
    val felt = feltForProductId(productId)
    val color = feltSurfaceColor(felt)
    val border = AppTheme.colors.border.color
    Box(
        modifier = modifier
            .size(size)
            .clip(Radii.R600.shape)
            .background(color)
            .border(1.dp, border, Radii.R600.shape),
    )
}

/**
 * Fraction of a square cosmetic tile that a card-back preview's card actually
 * paints horizontally. A card back is portrait, so it's narrower than its square
 * footprint and start-aligned inside it — the remaining `1 - fraction` on the
 * trailing side is dead space. Corner overlays (equipped / locked badges) use
 * [cardBackBadgeTrailingInset] to hug the card instead of floating in that gap.
 */
const val CardBackPreviewWidthFraction: Float = 0.7f

/**
 * How far to pull a corner badge in from a cosmetic tile's trailing edge so it
 * lands on the artwork. Card backs leave dead space on the right (see
 * [CardBackPreviewWidthFraction]); every other cosmetic fills its square
 * footprint, so their badge stays at the corner (inset 0).
 */
fun cardBackBadgeTrailingInset(productId: String, tileSize: Dp): Dp =
    if (cosmeticSlotFor(productId) == CosmeticSlot.CardBack) {
        tileSize * (1f - CardBackPreviewWidthFraction)
    } else {
        0.dp
    }

@Composable
private fun CardBackPreview(productId: String, size: Dp, modifier: Modifier = Modifier) {
    val style = cardBackForProductId(productId)
    val cardSize = PlayingCardSize(width = size * CardBackPreviewWidthFraction, height = size)
    // Avatar back is personal — without an overlay it would render as
    // the Default gray gradient in shop tiles, which understates what
    // the user is buying. Pass a generic placeholder emoji + color so
    // the catalog preview reads as "your avatar will go here."
    val avatarOverlay = if (style == CardBackStyle.Avatar) {
        AvatarBackOverlay(emoji = "🃏", backgroundColorHex = "#3B5BDB")
    } else {
        null
    }
    // Start-align, not center: a card back is narrower than its square
    // footprint, so centering insets its visible left edge from the tile
    // start. Every other preview kind (felt, pack, emoji) fills or
    // start-aligns its footprint, so a centered card made the card-back
    // shelf's tiles begin further in than the felt/emote shelves under the
    // same header. Start-aligning lines all shelves up (SHOP-6).
    Box(modifier = modifier.size(size), contentAlignment = Alignment.CenterStart) {
        PlayingCardBack(size = cardSize, style = style, avatarOverlay = avatarOverlay)
    }
}

@Composable
private fun EmojiTile(
    emoji: String,
    size: Dp,
    modifier: Modifier = Modifier,
    ringColor: Color? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(AppTheme.colors.surfaceRaised.color)
            .then(
                if (ringColor != null) {
                    Modifier.border(2.dp, ringColor, CircleShape)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
            // Scale the glyph to the tile — a fixed H600 looked tiny on the big
            // 100dp profile shelf tiles. Bigger tile, bigger emoji.
            typography = emojiTypographyFor(size),
        )
    }
}

@Composable
private fun emojiTypographyFor(size: Dp) = when {
    size >= 80.dp -> AppTheme.typography.Display.D1000
    size >= 56.dp -> AppTheme.typography.Heading.H1000
    size >= 40.dp -> AppTheme.typography.Heading.H800
    else -> AppTheme.typography.Heading.H600
}

@Preview
@Composable
private fun PreviewCosmeticPreview_Felts() {
    PreviewContent {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp),
        ) {
            CosmeticPreview(productId = "felt_royal_red", emoji = "🟥")
            CosmeticPreview(productId = "felt_midnight_blue", emoji = "🟦")
            CosmeticPreview(productId = "felt_charcoal", emoji = "⬛")
            CosmeticPreview(productId = "felt_pine_green", emoji = "🟩")
            CosmeticPreview(productId = "felt_sunset_weekend", emoji = "🌅")
        }
    }
}

@Preview
@Composable
private fun PreviewCosmeticPreview_CardBacks() {
    PreviewContent {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp),
        ) {
            CosmeticPreview(productId = "cardback_marble", emoji = "🃏")
            CosmeticPreview(productId = "cardback_gold", emoji = "🃏")
            CosmeticPreview(productId = "cardback_neon", emoji = "🃏")
            CosmeticPreview(productId = "cardback_diamond", emoji = "🃏")
            CosmeticPreview(productId = "cardback_comeback_kid", emoji = "🔥")
        }
    }
}

@Preview
@Composable
private fun PreviewCosmeticPreview_CardBacks_New() {
    PreviewContent {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp),
        ) {
            CosmeticPreview(productId = "cardback_skulls", emoji = "💀")
            CosmeticPreview(productId = "cardback_galaxy", emoji = "✨")
            CosmeticPreview(productId = "cardback_holographic", emoji = "🌈")
            CosmeticPreview(productId = "cardback_fire", emoji = "🔥")
            CosmeticPreview(productId = "cardback_avatar", emoji = "🪞")
        }
    }
}

@Preview
@Composable
private fun PreviewCosmeticPreview_CardBacks_Patterns() {
    PreviewContent {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp),
        ) {
            CosmeticPreview(productId = "cardback_lattice", emoji = "🃏")
            CosmeticPreview(productId = "cardback_hatching", emoji = "🃏")
            CosmeticPreview(productId = "cardback_crosshatch", emoji = "🃏")
            CosmeticPreview(productId = "cardback_dots", emoji = "🃏")
            CosmeticPreview(productId = "cardback_pinstripes", emoji = "🃏")
        }
    }
}

@Preview
@Composable
private fun PreviewCosmeticPreview_EmojiFallback() {
    PreviewContent {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp),
        ) {
            CosmeticPreview(productId = "emotes_drama", emoji = "💃")
            CosmeticPreview(productId = "avatars_animals", emoji = "🐶")
            CosmeticPreview(productId = "title_bluff_master", emoji = "🎯")
        }
    }
}

@Preview
@Composable
private fun PreviewCosmeticPreview_Packs() {
    PreviewContent {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp),
        ) {
            CosmeticPreview(
                productId = "avatars_animals",
                emoji = "🐶",
                packEmojis = listOf("🐶", "🐻", "🐰", "🐨"),
            )
            CosmeticPreview(
                productId = "emotes_baller",
                emoji = "💸",
                packEmojis = listOf("💸", "💎", "🤑", "📈"),
            )
            CosmeticPackThumbnail(emojis = listOf("👑", "🃏"), size = 64.dp)
        }
    }
}
