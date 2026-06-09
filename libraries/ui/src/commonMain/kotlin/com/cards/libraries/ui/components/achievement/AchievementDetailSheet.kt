package com.dangerfield.cards.libraries.ui.components.achievement

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_achievement_medallion_earned_back
import cards.libraries.resources.generated.resources.ui_achievement_medallion_how_to_earn
import cards.libraries.resources.generated.resources.ui_achievement_medallion_unlocks
import cards.libraries.resources.generated.resources.ui_achievement_sheet_mystery_body
import cards.libraries.resources.generated.resources.ui_achievement_sheet_mystery_title
import cards.libraries.resources.generated.resources.ui_achievement_sheet_progress
import cards.libraries.resources.generated.resources.ui_achievement_sheet_reward_xp
import cards.libraries.resources.generated.resources.ui_achievement_sheet_reward_xp_chips
import com.dangerfield.cards.libraries.cards.Achievement
import com.dangerfield.cards.libraries.cards.cosmeticRewardFor
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.ui.components.LevelProgressBar
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD400
import com.dangerfield.cards.system.VerticalSpacerD500
import org.jetbrains.compose.resources.stringResource

/**
 * An [AchievementMedal] that opens an [AchievementDetailSheet] on tap — the
 * default "tap a medal to read it" behavior for the grids (profile bookshelf,
 * Achievements page, Stats teaser). Surfaces that want a *different* tap
 * (e.g. Home's strip, which navigates to the full Achievements page) use the
 * plain [AchievementMedal] with their own `onClick` instead.
 *
 * [earnedAtEpochMs] is the unlock timestamp (null = not yet earned).
 * [progress] is the current count toward [Achievement.criterion] target,
 * used to show "how close" you are on a locked, quantitative achievement.
 */
@Composable
fun AchievementMedalWithDetail(
    achievement: Achievement,
    earnedAtEpochMs: Long?,
    progress: Int,
    modifier: Modifier = Modifier,
    size: MedalSize? = null,
) {
    var showSheet by remember { mutableStateOf(false) }
    AchievementMedal(
        achievement = achievement,
        earned = earnedAtEpochMs != null,
        // A preset [size] pins a fixed footprint (Home strip); otherwise the
        // caller's modifier governs (the profile / Achievements grids fill
        // their column).
        modifier = if (size != null) modifier.size(size.dp) else modifier,
        onClick = { showSheet = true },
    )
    if (showSheet) {
        AchievementDetailSheet(
            achievement = achievement,
            earnedAtEpochMs = earnedAtEpochMs,
            progress = progress,
            onDismiss = { showSheet = false },
        )
    }
}

/**
 * Preset medal footprints for surfaces that want a fixed size rather than
 * filling their column. Home's recent-achievements strip uses [Small]; the
 * profile / Achievements grids keep filling their column (pass no size).
 */
enum class MedalSize(val dp: Dp) {
    Small(88.dp),
    Medium(112.dp),
    Large(128.dp),
}

/**
 * Bottom sheet that reads out a single achievement: the big medal, its name,
 * what it is / how to earn it, a progress bar showing how close you are
 * (quantitative locked achievements), the reward, and any cosmetic it
 * unlocks. Mystery achievements stay a surprise — no name or how-to.
 */
@Composable
fun AchievementDetailSheet(
    achievement: Achievement,
    earnedAtEpochMs: Long?,
    progress: Int,
    onDismiss: () -> Unit,
) {
    val earned = earnedAtEpochMs != null
    val isMystery = achievement.isMystery && !earned
    val target = achievement.criterion.target

    BottomSheet(
        onDismissRequest = onDismiss,
        showCloseButton = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimension.D500),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AchievementMedal(
                achievement = achievement,
                earned = earned,
                modifier = Modifier.size(120.dp),
                earnedAtEpochMs = earnedAtEpochMs,
                progress = progress,
                flipOnInit = true,
                interactiveFlip = true,
            )
            VerticalSpacerD500()
            Text(
                text = if (isMystery) {
                    stringResource(Res.string.ui_achievement_sheet_mystery_title)
                } else {
                    achievement.name
                },
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )

            if (isMystery) {
                VerticalSpacerD300()
                Text(
                    text = stringResource(Res.string.ui_achievement_sheet_mystery_body),
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                    textAlign = TextAlign.Center,
                )
                VerticalSpacerD400()
                return@Column
            }

            if (achievement.description.isNotBlank()) {
                VerticalSpacerD200()
                // For a locked achievement the description doubles as the
                // "how to earn" hint, so label it as such.
                if (!earned) {
                    Text(
                        text = stringResource(Res.string.ui_achievement_medallion_how_to_earn),
                        typography = AppTheme.typography.Label.L400,
                        color = AppTheme.colors.contentTertiary,
                        textAlign = TextAlign.Center,
                    )
                    VerticalSpacerD200()
                }
                Text(
                    text = achievement.description,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            // "How close" — only meaningful for unearned, multi-step goals.
            if (!earned && target > 1) {
                VerticalSpacerD400()
                LevelProgressBar(
                    fraction = (progress.toFloat() / target.toFloat()).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                )
                VerticalSpacerD200()
                Text(
                    text = stringResource(Res.string.ui_achievement_sheet_progress, progress, target),
                    typography = AppTheme.typography.Label.L400,
                    color = AppTheme.colors.contentSecondary,
                )
            }

            VerticalSpacerD400()
            Text(
                text = rewardLabel(achievement),
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.poker.chipGold,
                textAlign = TextAlign.Center,
            )

            cosmeticRewardFor(achievement.id)?.let { cosmetic ->
                VerticalSpacerD200()
                Text(
                    text = stringResource(Res.string.ui_achievement_medallion_unlocks, cosmetic.label),
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.contentSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            if (earned && earnedAtEpochMs != null) {
                VerticalSpacerD300()
                val ago = earnedAgo(
                    earnedAtEpochMs,
                    kotlin.time.Clock.System.now().toEpochMilliseconds(),
                ).label()
                Text(
                    text = stringResource(Res.string.ui_achievement_medallion_earned_back, ago),
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.contentSecondary,
                )
            }
            VerticalSpacerD400()
        }
    }
}

@Composable
private fun rewardLabel(achievement: Achievement): String =
    if (achievement.chipReward > 0L) {
        stringResource(
            Res.string.ui_achievement_sheet_reward_xp_chips,
            achievement.xpReward,
            formatThousands(achievement.chipReward),
        )
    } else {
        stringResource(Res.string.ui_achievement_sheet_reward_xp, achievement.xpReward)
    }
