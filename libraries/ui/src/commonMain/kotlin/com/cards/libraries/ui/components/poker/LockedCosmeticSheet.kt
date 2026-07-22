package com.dangerfield.cards.libraries.ui.components.poker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.profile_item_sheet_in_pack
import cards.libraries.resources.generated.resources.profile_item_sheet_locked_not_owned
import cards.libraries.resources.generated.resources.profile_item_sheet_open_in_shop
import com.dangerfield.cards.libraries.cards.CosmeticSlot
import com.dangerfield.cards.libraries.cards.cosmeticSlotFor
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800
import org.jetbrains.compose.resources.stringResource

/**
 * A not-yet-owned cosmetic the user could buy, surfaced as a dimmed tile
 * after the owned items on a shoppable shelf (card backs, felts, avatar /
 * emote packs). Tapping routes to the shop. Price is intentionally omitted —
 * the tile is a "there's more in the shop" nudge, not a purchase surface.
 *
 * Lives in :libraries:ui (not a feature impl) because two features render it:
 * the profile bookshelf and the create-room cosmetic picker (ROOM-20).
 */
data class BuyableCosmetic(
    val productId: String,
    val title: String,
    val iconEmoji: String,
    val packEmojis: List<String> = emptyList(),
)

/**
 * The "you don't own this yet" sheet for a locked cosmetic: the hero preview
 * as a real thing, a "not yours yet" line, the pack contents if it's a pack,
 * and a single CTA into the shop's purchase flow for this product. Shared by
 * the profile bookshelf and the create-room picker so a locked item opens the
 * same dialog everywhere.
 */
@Composable
fun LockedCosmeticSheet(
    item: BuyableCosmetic,
    onOpenInShop: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheet(
        onDismissRequest = onDismiss,
        showCloseButton = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimension.D500, vertical = Dimension.D400),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CosmeticHero(
                productId = item.productId,
                emoji = item.iconEmoji,
                packEmojis = item.packEmojis,
            )
            VerticalSpacerD500()

            Text(
                text = item.title,
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )
            VerticalSpacerD200()
            Text(
                text = stringResource(Res.string.profile_item_sheet_locked_not_owned),
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )

            if (item.packEmojis.isNotEmpty()) {
                VerticalSpacerD500()
                PackContents(emojis = item.packEmojis)
            }

            VerticalSpacerD800()
            Button(
                onClick = {
                    onOpenInShop(item.productId)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                size = ButtonSize.Medium,
                style = ButtonStyle.Filled,
            ) {
                Text(stringResource(Res.string.profile_item_sheet_open_in_shop))
            }
        }
    }
}

/**
 * The hero preview. Card backs get the real flip-card (auto-flip on open +
 * draggable); felts get a felt-table vignette; everything else falls back to
 * its glyph on a raised tile.
 */
@Composable
fun CosmeticHero(
    productId: String,
    emoji: String,
    packEmojis: List<String>,
    emojiOverride: String? = null,
) {
    when (cosmeticSlotFor(productId)) {
        CosmeticSlot.CardBack -> FlippableCard(
            style = cardBackForProductId(productId),
            size = PlayingCardSize.Hole,
            flipOnInit = true,
            interactive = true,
        )
        CosmeticSlot.Felt -> FeltVignette(productId)
        // Packs (avatars / emotes) read as a stack of their contents.
        else -> if (emojiOverride == null && packEmojis.size >= 2) {
            CosmeticPackThumbnail(emojis = packEmojis, size = 132.dp)
        } else {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .clip(Radii.R700.shape)
                    .background(AppTheme.colors.surfaceRaised.color),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = emojiOverride ?: emoji,
                    typography = AppTheme.typography.Display.D900,
                )
            }
        }
    }
}

/** The "In this pack" grid of bundled emojis for avatar / emote packs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PackContents(emojis: List<String>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.profile_item_sheet_in_pack),
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.contentTertiary,
        )
        VerticalSpacerD200()
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimension.D300, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(Dimension.D300),
        ) {
            emojis.forEach { emoji ->
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.surfaceRaised.color),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = emoji, typography = AppTheme.typography.Heading.H600)
                }
            }
        }
    }
}

/**
 * A felt swatch staged as a tiny table — the felt color filling a rounded
 * surface with a pair of face-down cards laid on it, so a player sees how the
 * felt reads in play rather than as a bare color chip.
 */
@Composable
private fun FeltVignette(productId: String) {
    val color = feltSurfaceColor(feltForProductId(productId))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(Radii.Card.shape)
            .background(color)
            .border(1.dp, AppTheme.colors.border.color, Radii.Card.shape),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayingCardBack(size = PlayingCardSize.Deck)
            PlayingCardBack(size = PlayingCardSize.Deck)
        }
    }
}
