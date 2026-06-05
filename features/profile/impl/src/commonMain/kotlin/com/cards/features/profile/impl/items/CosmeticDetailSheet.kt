package com.dangerfield.cards.features.profile.impl.items

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import cards.libraries.resources.generated.resources.profile_item_sheet_bought
import cards.libraries.resources.generated.resources.profile_item_sheet_bought_free
import cards.libraries.resources.generated.resources.profile_item_sheet_earned
import cards.libraries.resources.generated.resources.profile_item_sheet_in_pack
import cards.libraries.resources.generated.resources.profile_item_sheet_try_emote
import cards.libraries.resources.generated.resources.profile_my_items_button_equip
import cards.libraries.resources.generated.resources.profile_my_items_button_unequip
import cards.libraries.resources.generated.resources.profile_my_items_personal_cosmetic_tag
import com.dangerfield.cards.libraries.cards.AcquisitionSource
import com.dangerfield.cards.libraries.cards.CosmeticSlot
import com.dangerfield.cards.libraries.cards.cosmeticSlotFor
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.cards.isPersonalCosmetic
import com.dangerfield.cards.libraries.ui.components.achievement.earnedAgo
import com.dangerfield.cards.libraries.ui.components.achievement.label
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.poker.CosmeticPackThumbnail
import com.dangerfield.cards.libraries.ui.components.poker.FlippableCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardBack
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.poker.cardBackForProductId
import com.dangerfield.cards.libraries.ui.components.poker.feltForProductId
import com.dangerfield.cards.libraries.ui.components.poker.feltSurfaceColor
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800
import org.jetbrains.compose.resources.stringResource

/**
 * The "look at the thing you own" sheet for a single cosmetic on the profile
 * bookshelf — the cosmetics counterpart to [com.dangerfield.cards.libraries.ui.components.achievement.AchievementDetailSheet].
 *
 * A hero preview leads (a card back flips over on open like a trophy; a felt
 * shows a table vignette; anything else shows its glyph), then the name,
 * description, how/when it was acquired, the "only you see this" note for
 * personal cosmetics, and an equip/unequip CTA for equippable items.
 */
@Composable
fun CosmeticDetailSheet(
    item: OwnedItem,
    onToggleEquip: (String) -> Unit,
    onDismiss: () -> Unit,
    onTryEmote: (String) -> Unit = {},
) {
    val isEmotePack = item.productId.startsWith("emotes_") && item.packEmojis.isNotEmpty()
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
            CosmeticHero(item = item)
            VerticalSpacerD500()

            Text(
                text = item.title,
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )

            item.description?.takeIf { it.isNotBlank() }?.let { desc ->
                VerticalSpacerD200()
                Text(
                    text = desc,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            // For packs (avatars / emotes), show the bundled emojis so the
            // sheet says what the user actually got — not just a glyph.
            if (item.packEmojis.isNotEmpty()) {
                VerticalSpacerD500()
                PackContents(emojis = item.packEmojis)
            }

            acquisitionLine(item)?.let { line ->
                VerticalSpacerD300()
                Text(
                    text = line,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.contentSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            if (isPersonalCosmetic(item.productId)) {
                VerticalSpacerD200()
                Text(
                    text = stringResource(Res.string.profile_my_items_personal_cosmetic_tag),
                    typography = AppTheme.typography.Label.L400,
                    color = AppTheme.colors.contentTertiary,
                    textAlign = TextAlign.Center,
                )
            }

            if (isEmotePack) {
                // Emote packs aren't "equipped" — they fill the in-game blast
                // tray. Let the user fire one off to see the blast for real.
                VerticalSpacerD800()
                Button(
                    onClick = {
                        onTryEmote(item.packEmojis.first())
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    size = ButtonSize.Medium,
                    style = ButtonStyle.Filled,
                ) {
                    Text(stringResource(Res.string.profile_item_sheet_try_emote))
                }
            } else if (item.isEquippable) {
                VerticalSpacerD800()
                Button(
                    onClick = { onToggleEquip(item.productId) },
                    modifier = Modifier.fillMaxWidth(),
                    size = ButtonSize.Medium,
                    style = if (item.isEquipped) ButtonStyle.Outlined else ButtonStyle.Filled,
                ) {
                    Text(
                        if (item.isEquipped) {
                            stringResource(Res.string.profile_my_items_button_unequip)
                        } else {
                            stringResource(Res.string.profile_my_items_button_equip)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The "In this pack" grid — every emoji the pack bundles, on its own raised
 * tile, so the user sees the full contents rather than a single hero glyph.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackContents(emojis: List<String>) {
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
                        .size(48.dp)
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
 * The hero preview. Card backs get the real flip-card (auto-flip on open +
 * draggable); felts get a felt-table vignette; everything else falls back to
 * its glyph on a raised tile.
 */
@Composable
private fun CosmeticHero(item: OwnedItem) {
    when (cosmeticSlotFor(item.productId)) {
        CosmeticSlot.CardBack -> FlippableCard(
            style = cardBackForProductId(item.productId),
            size = PlayingCardSize.Hole,
            flipOnInit = true,
            interactive = true,
        )
        CosmeticSlot.Felt -> FeltVignette(item.productId)
        // Packs (avatars / emotes) read as a stack of their contents.
        else -> if (item.packEmojis.size >= 2) {
            CosmeticPackThumbnail(emojis = item.packEmojis, size = 120.dp)
        } else {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(Radii.R700.shape)
                    .background(AppTheme.colors.surfaceRaised.color),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = item.iconEmoji, typography = AppTheme.typography.Display.D900)
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
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayingCardBack(size = PlayingCardSize.Deck)
            PlayingCardBack(size = PlayingCardSize.Deck)
        }
    }
}

@Composable
private fun acquisitionLine(item: OwnedItem): String? {
    if (item.acquiredAtEpochMs <= 0L) return null
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val ago = earnedAgo(item.acquiredAtEpochMs, now).label()
    return when {
        item.acquisitionSource == AcquisitionSource.Earned ->
            stringResource(Res.string.profile_item_sheet_earned, ago)
        item.costChipsAtPurchase > 0L ->
            stringResource(Res.string.profile_item_sheet_bought, ago, formatThousands(item.costChipsAtPurchase))
        else ->
            stringResource(Res.string.profile_item_sheet_bought_free, ago)
    }
}
