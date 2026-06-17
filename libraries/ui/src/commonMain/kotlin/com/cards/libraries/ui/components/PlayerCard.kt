package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.Achievement
import com.dangerfield.cards.libraries.cards.AllAchievements
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.achievement.AchievementMedal
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.VerticalSpacerD300
import org.jetbrains.compose.ui.tooling.preview.Preview

/** Most featured badges a Player Card will render, even if more are passed. */
private const val MaxFeaturedBadges = 3

private val FeaturedBadgeSize: Dp = 48.dp

/**
 * One display-ready stat on a Player Card — a value over a caption (e.g.
 * "1,204" / "Hands played", "Feb 2026" / "Member since"). Presentational only:
 * callers format the strings; the card just lays them out. Keeps the DS
 * component out of the progression/profile domain.
 */
data class PlayerCardStat(val value: String, val label: String)

/**
 * The shared identity block of the canonical Player Card — everything *below*
 * the avatar: display name, an optional "Lvl N" chip, the equipped title flair,
 * the permanent badge (e.g. 🏛 Founding member), and up to [MaxFeaturedBadges]
 * featured achievement medals.
 *
 * The avatar is intentionally NOT part of this block: each surface supplies it
 * the way it can — the at-table / self sheet renders it as the notch bubble,
 * the Edit Profile View tab renders an [AvatarCircle] above this. Keeping the
 * content here is what stops the surfaces from drifting.
 *
 * Presentational only: callers resolve the data (title label, permanent-badge
 * glyph, already-earned [featuredBadges]) and pass it in.
 */
@Composable
fun PlayerCardContent(
    name: String,
    modifier: Modifier = Modifier,
    level: Int? = null,
    equippedTitle: String? = null,
    permanentBadgeEmoji: String? = null,
    featuredBadges: List<Achievement> = emptyList(),
    stats: List<PlayerCardStat> = emptyList(),
) {
    val badges = featuredBadges.take(MaxFeaturedBadges)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimension.D300),
    ) {
        Text(
            text = name,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.content,
            textAlign = TextAlign.Center,
        )
        // Flair row — level chip, permanent badge, equipped title. Collapses
        // to nothing when the player has none of them.
        if (level != null || permanentBadgeEmoji != null || !equippedTitle.isNullOrBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimension.D200),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                level?.let {
                    StatusPill(
                        text = "Lvl $it",
                        background = AppTheme.colors.surfaceHigh,
                        foreground = AppTheme.colors.contentSecondary,
                    )
                }
                permanentBadgeEmoji?.let {
                    Text(text = it, typography = AppTheme.typography.Heading.H600)
                }
                equippedTitle?.takeIf { it.isNotBlank() }?.let { title ->
                    StatusPill(
                        text = title,
                        background = AppTheme.colors.accentPrimarySubtle,
                        foreground = AppTheme.colors.accentPrimary,
                    )
                }
            }
        }
        if (badges.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimension.D300),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                badges.forEach { badge ->
                    AchievementMedal(
                        achievement = badge,
                        earned = true,
                        modifier = Modifier.size(FeaturedBadgeSize),
                    )
                }
            }
        }
        if (stats.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimension.D700),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                stats.forEach { stat -> PlayerCardStatItem(stat) }
            }
        }
    }
}

@Composable
private fun PlayerCardStatItem(stat: PlayerCardStat) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stat.value,
            typography = AppTheme.typography.Heading.H600,
            color = AppTheme.colors.content,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stat.label,
            typography = AppTheme.typography.Caption.C400,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview
@Composable
private fun PlayerCardContentPreview_Full() {
    PreviewContent {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AvatarCircle(name = "Ramona", size = 100.dp, emoji = "🦊", backgroundColorHex = "#3B6D11")
            VerticalSpacerD300()
            PlayerCardContent(
                name = "Ramona",
                level = 14,
                equippedTitle = "High Roller",
                permanentBadgeEmoji = "🏛",
                featuredBadges = AllAchievements.take(3),
                stats = listOf(
                    PlayerCardStat(value = "1,204", label = "Hands played"),
                    PlayerCardStat(value = "Feb 2026", label = "Member since"),
                ),
            )
        }
    }
}

@Preview
@Composable
private fun PlayerCardContentPreview_Minimal() {
    PreviewContent {
        PlayerCardContent(name = "Guest")
    }
}
