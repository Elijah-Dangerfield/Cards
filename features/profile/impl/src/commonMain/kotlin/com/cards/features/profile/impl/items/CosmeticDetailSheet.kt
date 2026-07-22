package com.dangerfield.cards.features.profile.impl.items

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import cards.libraries.resources.generated.resources.earned_founding_member_thanks
import cards.libraries.resources.generated.resources.earned_founding_member_title
import cards.libraries.resources.generated.resources.founding_member_done
import cards.libraries.resources.generated.resources.founding_member_tagline
import cards.libraries.resources.generated.resources.profile_item_sheet_bought
import cards.libraries.resources.generated.resources.profile_item_sheet_avatar_edit_hint
import cards.libraries.resources.generated.resources.profile_item_sheet_bought_free
import cards.libraries.resources.generated.resources.profile_item_sheet_how_earned
import cards.libraries.resources.generated.resources.profile_item_sheet_earned
import cards.libraries.resources.generated.resources.profile_item_sheet_try_emote
import cards.libraries.resources.generated.resources.profile_items_equipped
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
import com.dangerfield.cards.libraries.ui.components.RotatingDial
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.poker.CosmeticHero
import com.dangerfield.cards.libraries.ui.components.poker.PackContents
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

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
    // The founding-member badge gets its own ceremonial sheet rather than the
    // generic earned-item layout — a rotating sun-dial behind the medallion.
    if (item.productId == FOUNDING_MEMBER_PRODUCT_ID) {
        FoundingMemberSheet(onDismiss = onDismiss)
        return
    }
    val isEmotePack = item.productId.startsWith("emotes_") && item.packEmojis.isNotEmpty()
    val isAvatarPack = item.productId.startsWith("avatars_") && item.packEmojis.isNotEmpty()
    // Earned / prestige grants arrive without catalog metadata; this client
    // map gives the known ones a real name + ceremony instead of a bare 🎁.
    val earnedInfo = KnownEarnedItems[item.productId]
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
                emojiOverride = earnedInfo?.emoji,
            )
            VerticalSpacerD500()

            Text(
                text = earnedInfo?.title?.let { stringResource(it) } ?: item.title,
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )

            val description = earnedInfo?.description?.let { stringResource(it) }
                ?: item.description?.takeIf { it.isNotBlank() }
            description?.let { desc ->
                VerticalSpacerD200()
                Text(
                    text = desc,
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.contentSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            if (earnedInfo != null) {
                VerticalSpacerD500()
                EarnedStory(info = earnedInfo)
            }

            // For packs (avatars / emotes), show the bundled emojis so the
            // sheet says what the user actually got — not just a glyph.
            if (item.packEmojis.isNotEmpty()) {
                VerticalSpacerD500()
                PackContents(emojis = item.packEmojis)
            }

            if (isAvatarPack) {
                // Avatar packs aren't equipped from this sheet — you pick one
                // option as your avatar in Edit profile. Point there.
                VerticalSpacerD300()
                Text(
                    text = stringResource(Res.string.profile_item_sheet_avatar_edit_hint),
                    typography = AppTheme.typography.Label.L400,
                    color = AppTheme.colors.contentTertiary,
                    textAlign = TextAlign.Center,
                )
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
            } else if (item.isEquippable && !isAvatarPack) {
                VerticalSpacerD800()
                EquipButton(item = item, onToggleEquip = onToggleEquip)
            }
        }
    }
}

/**
 * The founding-member badge's one-off sheet. A slowly-rotating golden sun-dial
 * frames the medallion, then a single tagline and a thank-you — deliberately
 * lighter on copy than the generic earned-item layout so the badge reads as a
 * moment rather than a spec sheet.
 */
@Composable
private fun FoundingMemberSheet(onDismiss: () -> Unit) {
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
            RotatingDial(size = 200.dp) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.surfaceRaised.color),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = KnownEarnedItems[FOUNDING_MEMBER_PRODUCT_ID]?.emoji ?: "🏛",
                        typography = AppTheme.typography.Display.D900,
                    )
                }
            }
            VerticalSpacerD500()
            Text(
                text = stringResource(Res.string.earned_founding_member_title),
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )
            VerticalSpacerD200()
            Text(
                text = stringResource(Res.string.founding_member_tagline),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
            VerticalSpacerD300()
            Text(
                text = stringResource(Res.string.earned_founding_member_thanks),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )
            VerticalSpacerD800()
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                size = ButtonSize.Medium,
                style = ButtonStyle.Filled,
            ) {
                Text(stringResource(Res.string.founding_member_done))
            }
        }
    }
}

/**
 * The equip CTA. Card backs and felts are *swap-only* — there's always one
 * equipped and you change it by equipping a different one, so an equipped
 * one shows a disabled "Equipped" state rather than an "Unequip" button that
 * would leave the slot empty. Slots that can legitimately sit empty (titles)
 * keep the unequip affordance.
 */
@Composable
private fun EquipButton(item: OwnedItem, onToggleEquip: (String) -> Unit) {
    val swapOnly = cosmeticSlotFor(item.productId)
        .let { it == CosmeticSlot.CardBack || it == CosmeticSlot.Felt }
    if (item.isEquipped && swapOnly) {
        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
            size = ButtonSize.Medium,
            style = ButtonStyle.Outlined,
            enabled = false,
        ) {
            Text(stringResource(Res.string.profile_items_equipped))
        }
        return
    }
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

/**
 * The ceremonial "how you earned it" block for prestige grants — a small
 * heading, the earn story, and an optional thank-you line so an earned item
 * reads as a moment rather than a bare glyph.
 */
@Composable
private fun EarnedStory(info: EarnedItemInfo) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.profile_item_sheet_how_earned),
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.contentTertiary,
        )
        VerticalSpacerD200()
        Text(
            text = stringResource(info.howEarned),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
        )
        info.thanks?.let { thanks ->
            VerticalSpacerD300()
            Text(
                text = stringResource(thanks),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun acquisitionLine(item: OwnedItem): String? {
    val kind = acquisitionLineKind(item) ?: return null
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val ago = earnedAgo(item.acquiredAtEpochMs, now).label()
    return when (kind) {
        AcquisitionLineKind.Earned ->
            stringResource(Res.string.profile_item_sheet_earned, ago)
        is AcquisitionLineKind.Bought ->
            stringResource(Res.string.profile_item_sheet_bought, ago, formatThousands(kind.costChips))
        AcquisitionLineKind.BoughtFree ->
            stringResource(Res.string.profile_item_sheet_bought_free, ago)
    }
}

private fun previewOwnedItem(
    productId: String,
    title: String,
    iconEmoji: String,
    subtitle: String = "",
    description: String? = null,
    isEquipped: Boolean = false,
    isEquippable: Boolean = true,
    acquisitionSource: AcquisitionSource = AcquisitionSource.Purchased,
    acquiredDaysAgo: Int = 3,
    costChipsAtPurchase: Long = 0L,
    packEmojis: List<String> = emptyList(),
) = OwnedItem(
    productId = productId,
    title = title,
    subtitle = subtitle,
    description = description,
    iconEmoji = iconEmoji,
    isEquipped = isEquipped,
    isEquippable = isEquippable,
    acquisitionSource = acquisitionSource,
    acquiredAtEpochMs = kotlin.time.Clock.System.now().toEpochMilliseconds() -
        acquiredDaysAgo * 24L * 60L * 60L * 1000L,
    costChipsAtPurchase = costChipsAtPurchase,
    packEmojis = packEmojis,
)

@Preview
@Composable
private fun CosmeticDetailSheetPreview_CardBackBought() {
    PreviewContent {
        CosmeticDetailSheet(
            item = previewOwnedItem(
                productId = "cardback_galaxy",
                title = "Galaxy",
                iconEmoji = "🌌",
                description = "A swirling nebula on every card back.",
                costChipsAtPurchase = 5_000,
            ),
            onToggleEquip = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun CosmeticDetailSheetPreview_FeltEquipped() {
    PreviewContent {
        CosmeticDetailSheet(
            item = previewOwnedItem(
                productId = "felt_midnight_blue",
                title = "Midnight Blue",
                iconEmoji = "🟦",
                description = "A deep blue felt for late-night tables.",
                isEquipped = true,
                costChipsAtPurchase = 3_000,
            ),
            onToggleEquip = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun CosmeticDetailSheetPreview_EmotePack() {
    PreviewContent {
        CosmeticDetailSheet(
            item = previewOwnedItem(
                productId = "emotes_baller",
                title = "Baller Pack",
                iconEmoji = "🏀",
                description = "Big plays deserve big reactions.",
                isEquippable = false,
                packEmojis = listOf("🏀", "🔥", "💪", "😤", "🏆"),
            ),
            onToggleEquip = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun CosmeticDetailSheetPreview_AvatarPack() {
    PreviewContent {
        CosmeticDetailSheet(
            item = previewOwnedItem(
                productId = "avatars_animals",
                title = "Animal Pack",
                iconEmoji = "🦊",
                description = "A menagerie of table personas.",
                isEquippable = false,
                packEmojis = listOf("🦊", "🐼", "🦁", "🐸", "🦉", "🐙"),
            ),
            onToggleEquip = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun CosmeticDetailSheetPreview_EarnedCardBack() {
    PreviewContent {
        CosmeticDetailSheet(
            item = previewOwnedItem(
                productId = "cardback_comeback_kid",
                title = "Comeback Kid",
                iconEmoji = "🃏",
                description = "For winning a hand after being down to your last chips.",
                acquisitionSource = AcquisitionSource.Earned,
                acquiredDaysAgo = 12,
            ),
            onToggleEquip = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun CosmeticDetailSheetPreview_FoundingMember() {
    PreviewContent {
        CosmeticDetailSheet(
            item = previewOwnedItem(
                productId = FOUNDING_MEMBER_PRODUCT_ID,
                title = "Founding Member",
                iconEmoji = "🏛",
                isEquippable = false,
                acquisitionSource = AcquisitionSource.Earned,
            ),
            onToggleEquip = {},
            onDismiss = {},
        )
    }
}
