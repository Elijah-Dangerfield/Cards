package com.dangerfield.cards.libraries.ui.components.achievement

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.Achievement
import com.dangerfield.cards.libraries.cards.cosmeticRewardFor
import com.dangerfield.cards.libraries.cards.formatThousands
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_achievement_medallion_earned_back
import cards.libraries.resources.generated.resources.ui_achievement_medallion_earned_label
import cards.libraries.resources.generated.resources.ui_achievement_medallion_how_to_earn
import cards.libraries.resources.generated.resources.ui_achievement_medallion_locked_label
import cards.libraries.resources.generated.resources.ui_achievement_medallion_unlocks
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Apple-Fitness-style achievement card. Three states:
 *
 *  - **Earned**: full color, slow shimmer sweep over the rarity tint. Tap
 *    flips to the back to read description + earned date (unless [onClick]
 *    is provided — then tap fires the external callback instead).
 *  - **Locked (non-mystery)**: same layout as earned but dimmed (0.45 alpha)
 *    and no shimmer — the player can see *what* the achievement is, *how
 *    far along they are* (e.g. "47 / 500" for a quantitative criterion),
 *    and tap to read *how to earn it*. The chase-goal state.
 *  - **Mystery** (`isMystery = true` and not earned): "?" + "Locked", no flip.
 *    Pure surprise — discoverable only by playing.
 *
 * @param onClick when null, the medallion handles its own tap by flipping
 *   between front and back (the Stats / Achievements page treatment). When
 *   non-null, the flip is suppressed and the callback fires instead — used
 *   on shelves (e.g. Home's recent-unlocks strip) where the tap should
 *   navigate elsewhere rather than reveal the back face.
 */
@Composable
fun AchievementMedallion(
    achievement: Achievement,
    earnedAtEpochMs: Long?,
    progress: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val isEarned = earnedAtEpochMs != null
    val isMysteryLocked = achievement.isMystery && !isEarned
    val canFlip = onClick == null && !isMysteryLocked
    var flipped by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (flipped && canFlip) 180f else 0f,
        animationSpec = tween(durationMillis = 520),
        label = "medallion-flip",
    )
    val showingBack = rotation > 90f

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 14f * density
            }
            .clip(Radii.R900.shape)
            .let { mod ->
                when {
                    onClick != null && !isMysteryLocked -> mod.clickable(onClick = onClick)
                    canFlip -> mod.clickable { flipped = !flipped }
                    else -> mod
                }
            },
    ) {
        if (!showingBack) {
            MedallionFront(
                achievement = achievement,
                isEarned = isEarned,
                isMysteryLocked = isMysteryLocked,
                progress = progress,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationY = 180f },
            ) {
                MedallionBack(
                    achievement = achievement,
                    earnedAtEpochMs = earnedAtEpochMs,
                )
            }
        }
    }
}

@Composable
private fun MedallionFront(
    achievement: Achievement,
    isEarned: Boolean,
    isMysteryLocked: Boolean,
    progress: Int,
) {
    val rarityColor = achievement.rarity.toAccentColor()
    val rarityColorResource = remember(rarityColor) {
        ColorResource.FromColor(rarityColor, "achievement-rarity")
    }
    // Locked-non-mystery cards reuse the earned background then fade — players
    // see what the achievement looks like at full strength when they earn it,
    // not a totally different visual.
    val bgBrush = when {
        isMysteryLocked -> Brush.linearGradient(
            listOf(
                AppTheme.colors.surfaceRaised.color,
                AppTheme.colors.surface.color,
            ),
        )
        else -> Brush.linearGradient(
            listOf(rarityColor.copy(alpha = 0.35f), rarityColor.copy(alpha = 0.12f)),
        )
    }
    val dimmed = !isEarned && !isMysteryLocked
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
            .then(if (dimmed) Modifier.alpha(0.45f) else Modifier),
    ) {
        if (isEarned) ShimmerOverlay()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (isMysteryLocked) "?" else achievement.icon,
                typography = AppTheme.typography.Display.D1100,
                color = if (isMysteryLocked) AppTheme.colors.contentSecondary else AppTheme.colors.content,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isMysteryLocked) stringResource(Res.string.ui_achievement_medallion_locked_label) else achievement.name,
                typography = AppTheme.typography.Body.B500,
                color = if (isMysteryLocked) AppTheme.colors.contentSecondary else AppTheme.colors.content,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            if (!isMysteryLocked) {
                Spacer(modifier = Modifier.height(4.dp))
                val target = achievement.criterion.target
                val label = when {
                    isEarned -> stringResource(Res.string.ui_achievement_medallion_earned_label)
                    target > 1 -> "$progress / $target"
                    else -> "+${achievement.xpReward} XP"
                }
                Text(
                    text = label,
                    typography = AppTheme.typography.Label.L400,
                    color = rarityColorResource,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun MedallionBack(achievement: Achievement, earnedAtEpochMs: Long?) {
    val rarityColor = achievement.rarity.toAccentColor()
    val rarityColorResource = remember(rarityColor) {
        ColorResource.FromColor(rarityColor, "achievement-rarity-back")
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.surfaceRaised.color),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = earnedAtEpochMs
                    ?.let {
                        stringResource(
                            Res.string.ui_achievement_medallion_earned_back,
                            earnedAgo(it, kotlin.time.Clock.System.now().toEpochMilliseconds()).label(),
                        )
                    }
                    ?: stringResource(Res.string.ui_achievement_medallion_how_to_earn),
                typography = AppTheme.typography.Label.L400,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = achievement.description,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
                maxLines = 4,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = rewardLabel(achievement),
                typography = AppTheme.typography.Body.B500,
                color = rarityColorResource,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            cosmeticRewardFor(achievement.id)?.let { cosmetic ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.ui_achievement_medallion_unlocks, cosmetic.label),
                    typography = AppTheme.typography.Label.L400,
                    color = AppTheme.colors.contentSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

private fun rewardLabel(achievement: Achievement): String = buildString {
    append("+${achievement.xpReward} XP")
    if (achievement.chipReward > 0L) append(" · +${formatThousands(achievement.chipReward)} chips")
}

@Composable
private fun ShimmerOverlay() {
    val transition = rememberInfiniteTransition(label = "medallion-shimmer")
    val offset by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-offset",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = size.width * offset }
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.20f),
                        Color.White.copy(alpha = 0.10f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

@Preview
@Composable
private fun MedallionPreview_LockedVisible() {
    PreviewContent {
        AchievementMedallion(
            achievement = com.dangerfield.cards.libraries.cards.AllAchievements
                .first { !it.isMystery },
            earnedAtEpochMs = null,
            progress = 0,
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Preview
@Composable
private fun MedallionPreview_LockedInProgress() {
    // Pins the locked-quantitative state: a non-mystery, non-earned tile
    // with a real partial-progress counter — should render "47 / 500"
    // instead of the XP reward label.
    val handsAchievement = com.dangerfield.cards.libraries.cards.AllAchievements
        .first {
            !it.isMystery &&
                it.criterion is com.dangerfield.cards.libraries.cards.Criterion.HandsPlayed &&
                it.criterion.target == 500
        }
    PreviewContent {
        AchievementMedallion(
            achievement = handsAchievement,
            earnedAtEpochMs = null,
            progress = 47,
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Preview
@Composable
private fun MedallionPreview_LockedMystery() {
    PreviewContent {
        AchievementMedallion(
            achievement = com.dangerfield.cards.libraries.cards.AllAchievements
                .first { it.isMystery },
            earnedAtEpochMs = null,
            progress = 0,
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Preview
@Composable
private fun MedallionPreview_Earned() {
    PreviewContent {
        AchievementMedallion(
            achievement = com.dangerfield.cards.libraries.cards.AllAchievements[1],
            earnedAtEpochMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - (1000L * 60 * 60 * 24 * 3),
            progress = 100,
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Preview
@Composable
private fun MedallionPreview_LockedWithCosmeticReward() {
    // Non-mystery achievement that grants a cosmetic — the back face
    // should advertise it as a chase goal alongside the XP/chip reward.
    val achievement = com.dangerfield.cards.libraries.cards.AllAchievements
        .first { it.id == com.dangerfield.cards.libraries.cards.AchievementId.BOT_WHISPERER }
    PreviewContent {
        AchievementMedallion(
            achievement = achievement,
            earnedAtEpochMs = null,
            progress = 3,
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Preview
@Composable
private fun MedallionPreview_EarnedWithCosmeticReward() {
    // Mystery achievement that's now earned — the back face should name
    // the cosmetic so the user knows what they just unlocked.
    val achievement = com.dangerfield.cards.libraries.cards.AllAchievements
        .first { it.id == com.dangerfield.cards.libraries.cards.AchievementId.POT_5000 }
    PreviewContent {
        AchievementMedallion(
            achievement = achievement,
            earnedAtEpochMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - (1000L * 60 * 60 * 24),
            progress = achievement.criterion.target,
            modifier = Modifier.padding(8.dp),
        )
    }
}
