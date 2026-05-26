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
import androidx.compose.ui.draw.clip
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
 *  - **Emoji fallback** — for everything else (emote packs, avatar
 *    packs, utilities), draws the product's [emoji] inside a circle so
 *    the row never looks empty.
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
) {
    when (cosmeticSlotFor(productId)) {
        CosmeticSlot.Felt -> FeltSwatch(productId = productId, size = size, modifier = modifier)
        CosmeticSlot.CardBack -> CardBackPreview(productId = productId, size = size, modifier = modifier)
        else -> EmojiTile(emoji = emoji, size = size, modifier = modifier)
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
            .clip(Radii.R500.shape)
            .background(color)
            .border(1.dp, border, Radii.R500.shape),
    )
}

@Composable
private fun CardBackPreview(productId: String, size: Dp, modifier: Modifier = Modifier) {
    val style = cardBackForProductId(productId)
    val cardSize = PlayingCardSize(width = size * 0.7f, height = size)
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        PlayingCardBack(size = cardSize, style = style)
    }
}

@Composable
private fun EmojiTile(emoji: String, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(AppTheme.colors.surfaceSecondary.color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
            typography = AppTheme.typography.Heading.H600,
        )
    }
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
            CosmeticPreview(productId = "table_sunset", emoji = "🌅")
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
