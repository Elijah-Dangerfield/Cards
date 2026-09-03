package com.dangerfield.cards.libraries.ui.components.achievement

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import cards.libraries.resources.generated.resources.room_celebration_settings_hint
import cards.libraries.resources.generated.resources.room_celebration_subtitle_multi
import cards.libraries.resources.generated.resources.room_celebration_tap_to_reveal
import cards.libraries.resources.generated.resources.room_celebration_title_multi
import cards.libraries.resources.generated.resources.room_celebration_title_single
import com.dangerfield.cards.libraries.cards.AchievementRarity
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.cosmeticRewardFor
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.ConfettiBurst
import com.dangerfield.cards.libraries.ui.components.PagerIndicator
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.dialog.BubbleSurface
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.asDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD400
import com.dangerfield.cards.system.VerticalSpacerD500
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * Achievement-unlock celebration sheet — a slide-up bottom sheet giving each
 * earned achievement a focused reveal (big medallion, name, description, reward
 * summary, cosmetic gift preview) instead of cramming it into a hand-end dialog
 * row. Two surfaces share it, so it lives in the DS rather than one feature:
 *
 * - The bot-mode play screen sequences it *after* the showdown / bust dialog
 *   dismisses.
 * - Home replays it for achievements earned during a real-chip multiplayer game,
 *   which finished on the felt with no at-table reveal (PROG-13).
 *
 * When one batch earns multiple achievements they ride one sheet as a
 * horizontally-swiped pager with a dot indicator (PROG-9) rather than queuing as
 * a sequence of N modals. The first page auto-reveals; later pages keep the
 * tap-to-reveal mystery so the player still paces the celebration.
 */
@Composable
fun AchievementCelebrationSheet(
    earned: List<EarnedAchievement>,
    onContinue: () -> Unit,
    showSettingsHint: Boolean = false,
) {
    if (earned.isEmpty()) return

    val goldBubble = AppTheme.colors.poker.chipGold
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
        backgroundColor = AppTheme.colors.surface,
        dragHandle = handle,
        stickyTopContent = {
            Text(
                text = title,
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            VerticalSpacerD200()
            Text(
                text = titleSubtitle,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        stickyBottomContent = {
            ButtonPrimary(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                size = ButtonSize.Large,
            ) {
                Text(text = stringResource(Res.string.room_celebration_continue_button))
            }
        },
    ) {
        // Confetti rains over the whole sheet body the instant the first
        // medallion slams home — the same "you earned it" payoff the level-up
        // takeover gets, routed through the shared DS primitive so the feel
        // never drifts between the two celebration surfaces.
        var confettiOn by remember { mutableStateOf(false) }
        Box {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (earned.size == 1) {
                    CelebrationCard(
                        earned = earned.first(),
                        autoReveal = true,
                        onRevealComplete = { confettiOn = true },
                    )
                } else {
                    // Multi-unlock: one card per page, swiped horizontally with a
                    // dot indicator (PROG-9) — each unlock gets the full stage
                    // instead of shrinking into a scrolled stack. The first page
                    // auto-reveals; later pages keep the tap-to-reveal mystery so
                    // the player still paces the celebration.
                    //
                    // Keeping every page composed (beyondViewportPageCount) sizes
                    // the pager to its tallest card once, so swiping between
                    // cards of different heights never resizes the sheet; the
                    // animateContentSize covers the remaining growth when a
                    // tapped mystery card reveals content taller than the
                    // current max (PROG-10).
                    val pagerState = rememberPagerState(pageCount = { earned.size })
                    HorizontalPager(
                        state = pagerState,
                        pageSpacing = Dimension.D500,
                        verticalAlignment = Alignment.Top,
                        beyondViewportPageCount = earned.size,
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                    ) { page ->
                        CelebrationCard(
                            earned = earned[page],
                            autoReveal = page == 0,
                            onRevealComplete = if (page == 0) {
                                { confettiOn = true }
                            } else {
                                {}
                            },
                        )
                    }
                    VerticalSpacerD400()
                    PagerIndicator(
                        pageCount = earned.size,
                        currentPage = pagerState.currentPage,
                    )
                }
                // Quiet discoverability line for the Settings toggle — shown only the
                // first few celebrations (gated by the caller) so new users learn the
                // pop-ups can be silenced without it nagging every hand thereafter.
                if (showSettingsHint) {
                    VerticalSpacerD500()
                    Text(
                        text = stringResource(Res.string.room_celebration_settings_hint),
                        typography = AppTheme.typography.Body.B400,
                        color = AppTheme.colors.contentSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (confettiOn) {
                ConfettiBurst(modifier = Modifier.matchParentSize())
            }
        }
    }
}

@Composable
private fun CelebrationCard(
    earned: EarnedAchievement,
    autoReveal: Boolean,
    onRevealComplete: () -> Unit = {},
) {
    val achievement = earned.achievement
    val cosmetic = remember(achievement.id) { cosmeticRewardFor(achievement.id) }
    val accent = remember(achievement.rarity) { achievement.rarity.toCelebrationTint() }

    // The card chrome pops in (fade + scale) on first composition. Pager pages
    // all compose up front (the sheet sizes to the tallest card, PROG-10), so
    // for multi-unlocks this plays once at sheet entrance.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(achievement.id) { shown = true }

    // Per-card reveal gate. The first card (or any single-unlock celebration)
    // auto-reveals so the user always sees the reveal animation play once
    // without input; subsequent cards stay as a mystery `?` placeholder
    // until tapped, so the player paces the celebration one beat at a time
    // instead of watching N reveals fire in parallel.
    var revealed by remember(achievement.id) { mutableStateOf(autoReveal) }

    AnimatedVisibility(
        visible = shown,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Radii.Card.shape)
                .background(accent)
                .animateContentSize()
                .padding(horizontal = Dimension.D700, vertical = Dimension.D700),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 140dp keeps multi-unlock stacks scrollable while still giving
            // each medallion a satisfying footprint. AchievementUnlockReveal
            // owns the shake → burst → slam-in sequence + haptics; gating
            // it behind `revealed` means the sequence (and its haptic ticks)
            // only fires when the card is tapped, never on pre-reveal mount.
            Box(modifier = Modifier.size(REVEAL_SIZE)) {
                if (revealed) {
                    AchievementUnlockReveal(
                        achievement = achievement,
                        onSequenceComplete = onRevealComplete,
                    )
                } else {
                    MysteryRevealTrigger(onTap = { revealed = true })
                }
            }
            // Identity-bearing detail (name / description / reward / cosmetic
            // gift) is gated on `revealed` so the surprise is the medallion's
            // contents, not just its animation. Mystery cards show a "Tap to
            // reveal" hint instead so the affordance is unambiguous.
            AnimatedVisibility(
                visible = revealed,
                enter = fadeIn(),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    VerticalSpacerD400()
                    Text(
                        text = achievement.name,
                        typography = AppTheme.typography.Heading.H700,
                        color = AppTheme.colors.content,
                        textAlign = TextAlign.Center,
                    )
                    if (achievement.description.isNotBlank()) {
                        VerticalSpacerD200()
                        Text(
                            text = achievement.description,
                            typography = AppTheme.typography.Body.B500,
                            color = AppTheme.colors.contentSecondary,
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
                        color = AppTheme.colors.poker.chipGold,
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
                                color = AppTheme.colors.content,
                            )
                            Text(
                                text = stringResource(Res.string.room_achievement_cosmetic_label, reward.label),
                                typography = AppTheme.typography.Body.B500,
                                color = AppTheme.colors.content,
                            )
                        }
                        VerticalSpacerD200()
                        Text(
                            text = stringResource(Res.string.room_celebration_cosmetic_attribution, achievement.name),
                            typography = AppTheme.typography.Body.B400,
                            color = AppTheme.colors.contentSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = !revealed,
                enter = fadeIn(),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    VerticalSpacerD400()
                    Text(
                        text = stringResource(Res.string.room_celebration_tap_to_reveal),
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.contentSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun MysteryRevealTrigger(onTap: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(Radii.Card.shape)
            .background(AppTheme.colors.surfaceRaised.color)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "?",
            typography = AppTheme.typography.Display.D1400,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

private val REVEAL_SIZE = 140.dp

@Preview
@Composable
private fun AchievementCelebrationSheetPreview_Single() {
    PreviewContent {
        AchievementCelebrationSheet(
            earned = listOf(
                previewEarned(
                    name = "Pocket rockets",
                    description = "Win a hand with pocket aces.",
                    icon = "🚀",
                    rarity = AchievementRarity.RARE,
                    xpReward = 200,
                ),
            ),
            onContinue = {},
            showSettingsHint = true,
        )
    }
}

@Preview
@Composable
private fun AchievementCelebrationSheetPreview_WithCosmetic() {
    PreviewContent {
        AchievementCelebrationSheet(
            earned = listOf(
                previewEarned(
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
private fun AchievementCelebrationSheetPreview_PagedMultiple() {
    PreviewContent {
        AchievementCelebrationSheet(
            earned = listOf(
                previewEarned(
                    id = com.dangerfield.cards.libraries.cards.AchievementId.FIRST_HAND,
                    name = "First hand",
                    description = "Play your first hand of poker.",
                    icon = "🃏",
                    rarity = AchievementRarity.COMMON,
                    xpReward = 50,
                ),
                previewEarned(
                    id = com.dangerfield.cards.libraries.cards.AchievementId.FIRST_WIN_BY_FOLD,
                    name = "First fold-out win",
                    description = "Win a hand by getting everyone else to fold.",
                    icon = "🙅",
                    rarity = AchievementRarity.COMMON,
                    xpReward = 50,
                ),
                previewEarned(
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

private fun previewEarned(
    id: com.dangerfield.cards.libraries.cards.AchievementId =
        com.dangerfield.cards.libraries.cards.AchievementId.HANDS_10,
    name: String = "Getting started",
    description: String = "Play 10 hands.",
    icon: String = "🎯",
    rarity: AchievementRarity = AchievementRarity.COMMON,
    xpReward: Int = 50,
    chipReward: Long = 0L,
): EarnedAchievement = EarnedAchievement(
    achievement = com.dangerfield.cards.libraries.cards.Achievement(
        id = id,
        name = name,
        description = description,
        icon = icon,
        rarity = rarity,
        criterion = com.dangerfield.cards.libraries.cards.Criterion.HandsPlayed(10),
        xpReward = xpReward,
        chipReward = chipReward,
    ),
    earnedAtEpochMs = 0L,
)
