package com.dangerfield.cards.features.progression.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.Achievement
import com.dangerfield.cards.libraries.cards.AchievementRarity
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Apple-Fitness-style achievement card. Earned cards show the icon + name +
 * rarity color with a slow shimmer sweep, and tapping flips them to the
 * back to reveal criterion + earned date + reward. Locked cards are a
 * mystery — "?" glyph, no title, no description, no flip — so players have
 * to *discover* what unlocks them by playing.
 *
 * The card flips in-place via a Y-axis rotation; we swap the back content
 * once rotation crosses 90° so neither side renders mirrored.
 */
@Composable
fun AchievementMedallion(
    achievement: Achievement,
    earnedAtEpochMs: Long?,
    @Suppress("UNUSED_PARAMETER") progress: Int,
    modifier: Modifier = Modifier,
) {
    val isEarned = earnedAtEpochMs != null
    var flipped by remember { mutableStateOf(false) }
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (flipped && isEarned) 180f else 0f,
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
            .clip(RoundedCornerShape(24.dp))
            // Locked cards don't flip — the mystery is the whole point.
            .clickable(enabled = isEarned) { flipped = !flipped },
    ) {
        if (!showingBack) {
            MedallionFront(
                achievement = achievement,
                isEarned = isEarned,
            )
        } else {
            // Counter-rotate so the back content reads normally.
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
) {
    val rarityColor = achievement.rarity.color()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isEarned) {
                    Brush.linearGradient(
                        listOf(rarityColor.copy(alpha = 0.35f), rarityColor.copy(alpha = 0.12f)),
                    )
                } else {
                    // Locked: neutral surface, no rarity tint. The mystery
                    // shouldn't telegraph how special the achievement is.
                    Brush.linearGradient(
                        listOf(
                            AppTheme.colors.surfaceSecondary.color,
                            AppTheme.colors.surfacePrimary.color,
                        ),
                    )
                },
            ),
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
                text = if (isEarned) achievement.icon else "?",
                typography = AppTheme.typography.Display.D1200,
                color = if (isEarned) AppTheme.colors.text else AppTheme.colors.textSecondary,
            )
            if (isEarned) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = achievement.name,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.text,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            } else {
                // Locked: no name, no progress, no hint. Discoverable only
                // by playing — keeps the unlock moment a real surprise.
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Locked",
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun MedallionBack(achievement: Achievement, earnedAtEpochMs: Long?) {
    val rarityColor = achievement.rarity.color()
    val rarityColorResource = remember(rarityColor) {
        ColorResource.FromColor(rarityColor, "achievement-rarity")
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.surfaceSecondary.color),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = achievement.name,
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.text,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = achievement.description,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 5,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val rewardLabel = buildString {
                append("+${achievement.xpReward} XP")
                if (achievement.chipReward > 0L) {
                    append(" · +${achievement.chipReward} chips")
                }
            }
            Text(
                text = rewardLabel,
                typography = AppTheme.typography.Body.B500,
                color = rarityColorResource,
                textAlign = TextAlign.Center,
            )
            if (earnedAtEpochMs != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Earned ${formatRelativeDate(earnedAtEpochMs)}",
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
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


/**
 * Mid-fidelity relative date. "today" / "yesterday" / "N days ago" / "MMM dd"
 * for older. Avoids pulling in a full date library for one display string.
 */
@Composable
private fun formatRelativeDate(epochMs: Long): String {
    val now = remember { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    val daysAgo = ((now - epochMs) / (1000L * 60 * 60 * 24)).toInt()
    return when {
        daysAgo <= 0 -> "today"
        daysAgo == 1 -> "yesterday"
        daysAgo < 7 -> "$daysAgo days ago"
        daysAgo < 30 -> "${daysAgo / 7} weeks ago"
        else -> "${daysAgo / 30} months ago"
    }
}

private fun AchievementRarity.color(): Color = when (this) {
    AchievementRarity.COMMON -> Color(0xFFB08D57)    // bronze
    AchievementRarity.RARE -> Color(0xFFB0B0B8)      // silver
    AchievementRarity.EPIC -> PokerPalette.ChipGold  // gold
    AchievementRarity.LEGENDARY -> Color(0xFFE07AB1) // pink/violet — "legendary" pops
}

@Preview
@Composable
private fun MedallionPreview_LockedNoProgress() {
    PreviewContent {
        AchievementMedallion(
            achievement = com.dangerfield.cards.libraries.cards.AllAchievements[0],
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
