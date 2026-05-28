package com.dangerfield.cards.features.room.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_achievement_cosmetic_label
import cards.libraries.resources.generated.resources.room_achievement_reward_xp
import cards.libraries.resources.generated.resources.room_achievement_reward_xp_plus_chips
import cards.libraries.resources.generated.resources.room_celebration_continue_button
import cards.libraries.resources.generated.resources.room_celebration_cosmetic_attribution
import cards.libraries.resources.generated.resources.room_celebration_subtitle_multi
import cards.libraries.resources.generated.resources.room_celebration_title_multi
import cards.libraries.resources.generated.resources.room_celebration_title_single
import com.dangerfield.cards.libraries.cards.AchievementRarity
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.cosmeticRewardFor
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.achievement.AchievementUnlockReveal
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.dialog.BubbleSurface
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.asDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD400
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD700
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Bot-mode achievement-unlock celebration. Sequenced *after* the hand-end
 * dialog dismisses so the unlock gets a focused moment instead of being
 * crammed into a row inside the showdown summary. Each earned achievement
 * gets a big icon, name, description, reward summary, and a cosmetic
 * preview when the unlock also grants an inventory item.
 *
 * When a single hand earns multiple achievements (common on a new user's
 * first game), they stack into one sheet rather than queuing as a sequence
 * of N modals. MP-mode unlocks keep the legacy inline row inside
 * [HandResultDialogs] — see the `xpMode` branch on the call sites in
 * [PlayPokerScreen].
 */
@Composable
internal fun AchievementCelebrationSheet(
    earned: List<EarnedAchievement>,
    onContinue: () -> Unit,
) {
    if (earned.isEmpty()) return

    val goldBubble = ColorResource.FromColor(PokerPalette.ChipGold, "chip-gold")
    val handle: BottomSheetDragHandle = topAccessoryEmoji(
        emoji = "🎉",
        surface = BubbleSurface.Solid(goldBubble),
    ).asDragHandle()

    val title = if (earned.size == 1) {
        stringResource(Res.string.room_celebration_title_single)
    } else {
        stringResource(Res.string.room_celebration_title_multi, earned.size)
    }
    val titleSubtitle = if (earned.size == 1) {
        earned.first().achievement.name
    } else {
        stringResource(Res.string.room_celebration_subtitle_multi)
    }

    BottomSheet(
        onDismissRequest = onContinue,
        backgroundColor = AppTheme.colors.surfacePrimary,
        dragHandle = handle,
        title = {
            Text(
                text = title,
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.onSurfacePrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            VerticalSpacerD200()
            Text(
                text = titleSubtitle,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        earned.forEachIndexed { index, item ->
            if (index > 0) VerticalSpacerD500()
            CelebrationCard(earned = item, index = index)
        }
        VerticalSpacerD700()
        ButtonPrimary(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(Res.string.room_celebration_continue_button))
        }
    }
}

@Composable
private fun CelebrationCard(earned: EarnedAchievement, index: Int) {
    val achievement = earned.achievement
    val cosmetic = remember(achievement.id) { cosmeticRewardFor(achievement.id) }
    val accent = remember(achievement.rarity) { rarityAccent(achievement.rarity) }

    // Stagger the entrance so multi-unlock celebrations cascade rather than
    // popping in all at once. Each card waits 90ms longer than the prior one.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(achievement.id) {
        kotlinx.coroutines.delay(index * 90L)
        shown = true
    }

    AnimatedVisibility(
        visible = shown,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Radii.Card.shape)
                .background(accent)
                .padding(horizontal = Dimension.D700, vertical = Dimension.D700),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Mystery-card → shake → burst → medallion slam-in sequence.
            // Sits inside the per-card AnimatedVisibility above so the
            // card chrome fades in first and the reveal animation runs
            // against a solid surface. Sized at 140dp so multi-unlock
            // stacks stay scrollable while still giving each medallion
            // a satisfying footprint. Built-in haptics on each shake
            // peak + burst + medallion land — the sheet inherits the
            // Duolingo-style tactile beat the dialog already used for
            // tutorial completion.
            Box(modifier = Modifier.size(REVEAL_SIZE)) {
                AchievementUnlockReveal(achievement = achievement)
            }
            VerticalSpacerD400()
            Text(
                text = achievement.name,
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
                textAlign = TextAlign.Center,
            )
            if (achievement.description.isNotBlank()) {
                VerticalSpacerD200()
                Text(
                    text = achievement.description,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.onSurfaceSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            VerticalSpacerD400()
            val rewardText = if (achievement.chipReward > 0L) {
                stringResource(
                    Res.string.room_achievement_reward_xp_plus_chips,
                    achievement.xpReward,
                    formatThousands(achievement.chipReward),
                )
            } else {
                stringResource(Res.string.room_achievement_reward_xp, achievement.xpReward)
            }
            Text(
                text = rewardText,
                typography = AppTheme.typography.Body.B600,
                color = ColorResource.FromColor(PokerPalette.ChipGold, "chip-gold"),
                textAlign = TextAlign.Center,
            )
            cosmetic?.let { reward ->
                VerticalSpacerD300()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimension.D200),
                ) {
                    Text(
                        text = "🎁",
                        typography = AppTheme.typography.Body.B600,
                        color = AppTheme.colors.text,
                    )
                    Text(
                        text = stringResource(Res.string.room_achievement_cosmetic_label, reward.label),
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.onSurfacePrimary,
                    )
                }
                VerticalSpacerD200()
                Text(
                    text = stringResource(Res.string.room_celebration_cosmetic_attribution, achievement.name),
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.onSurfaceSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private val REVEAL_SIZE = 140.dp

private fun rarityAccent(rarity: AchievementRarity): androidx.compose.ui.graphics.Color = when (rarity) {
    AchievementRarity.COMMON -> PokerPalette.ChipGold.copy(alpha = 0.12f)
    AchievementRarity.RARE -> PokerPalette.ChipGold.copy(alpha = 0.18f)
    AchievementRarity.EPIC -> PokerPalette.ChipGold.copy(alpha = 0.24f)
    AchievementRarity.LEGENDARY -> PokerPalette.ChipGold.copy(alpha = 0.30f)
}

@Preview
@Composable
private fun AchievementCelebrationSheetPreview_Single() {
    PreviewContent {
        AchievementCelebrationSheet(
            earned = listOf(
                PreviewSamples.earnedAchievement(
                    name = "Pocket rockets",
                    description = "Win a hand with pocket aces.",
                    icon = "🚀",
                    rarity = AchievementRarity.RARE,
                    xpReward = 200,
                ),
            ),
            onContinue = {},
        )
    }
}

@Preview
@Composable
private fun AchievementCelebrationSheetPreview_WithCosmetic() {
    PreviewContent {
        AchievementCelebrationSheet(
            earned = listOf(
                PreviewSamples.earnedAchievement(
                    id = com.dangerfield.cards.libraries.cards.AchievementId.DONT_CALL_IT_COMEBACK,
                    name = "Don't call it a comeback",
                    description = "Dip to 10 BB, climb back to a full 100 BB stack.",
                    icon = "🎙️",
                    rarity = AchievementRarity.EPIC,
                    xpReward = 500,
                ),
            ),
            onContinue = {},
        )
    }
}

@Preview
@Composable
private fun AchievementCelebrationSheetPreview_StackedMultiple() {
    PreviewContent {
        AchievementCelebrationSheet(
            earned = listOf(
                PreviewSamples.earnedAchievement(
                    id = com.dangerfield.cards.libraries.cards.AchievementId.FIRST_HAND,
                    name = "First hand",
                    description = "Play your first hand of poker.",
                    icon = "🃏",
                    rarity = AchievementRarity.COMMON,
                    xpReward = 50,
                ),
                PreviewSamples.earnedAchievement(
                    id = com.dangerfield.cards.libraries.cards.AchievementId.FIRST_WIN_BY_FOLD,
                    name = "First fold-out win",
                    description = "Win a hand by getting everyone else to fold.",
                    icon = "🙅",
                    rarity = AchievementRarity.COMMON,
                    xpReward = 50,
                ),
                PreviewSamples.earnedAchievement(
                    id = com.dangerfield.cards.libraries.cards.AchievementId.POT_5000,
                    name = "Pot magnet",
                    description = "Win a 5,000-chip pot.",
                    icon = "🧲",
                    rarity = AchievementRarity.EPIC,
                    xpReward = 500,
                    chipReward = 1_000,
                ),
            ),
            onContinue = {},
        )
    }
}
