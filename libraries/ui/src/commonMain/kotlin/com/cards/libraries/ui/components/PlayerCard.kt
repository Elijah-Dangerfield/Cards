package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_level_chip_label
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD300
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * One display-ready stat on a Player Card — a value over a caption (e.g.
 * "1,204" / "Hands played", "Feb 2026" / "Member since"). Presentational only:
 * callers format the strings; the card just lays them out. Keeps the DS
 * component out of the progression/profile domain.
 */
data class PlayerCardStat(val value: String, val label: String)

/**
 * The shared identity block of the canonical Player Card — everything *below*
 * the avatar: display name, an optional "Lvl N" chip, and the player's
 * [badges] (founding-member-style badges + titles, now unified) as tappable
 * chips that open [onBadgeClick] to read about them.
 *
 * The avatar is intentionally NOT part of this block: each surface supplies it
 * the way it can — the at-table / self sheet renders it as the notch bubble,
 * the Edit Profile View tab renders an [AvatarCircle] above this. Keeping the
 * content here is what stops the surfaces from drifting.
 *
 * Presentational only: callers resolve the [badges] (via [resolvePlayerBadges])
 * and pass them in. Recent achievements deliberately no longer render here — the
 * card is identity + prestige badges; achievements live on the profile.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerCardContent(
    name: String,
    modifier: Modifier = Modifier,
    level: Int? = null,
    badges: List<PlayerBadge> = emptyList(),
    onBadgeClick: (PlayerBadge) -> Unit = {},
    stats: List<PlayerCardStat> = emptyList(),
) {
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
        // Flair row — level chip + tappable badge chips. Collapses to nothing
        // when the player has neither.
        if (level != null || badges.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dimension.D200, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Dimension.D200),
            ) {
                level?.let { LevelChip(level = it) }
                badges.forEach { badge ->
                    BadgeChip(badge = badge, onClick = { onBadgeClick(badge) })
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

/**
 * The level chip on a Player Card — the progression sparkle disc next to
 * "Lvl N" in the progression accent on its subtle teal tint. Carries the same
 * teal/sparkle identity the Profile level summary and Home pill wear, so the
 * level reads as *progression* with weight rather than a neutral grey chip
 * lost among the badges beside it (CARDS-1P).
 *
 * Static and progress-less: an opponent's card only knows their bare level,
 * not their XP-into-level, so the disc renders at a full ring rather than an
 * honest partial fill. For the interactive, progress-aware header pill use
 * [LevelPill].
 */
@Composable
private fun LevelChip(level: Int) {
    StatusPill(
        background = AppTheme.colors.accentSecondarySubtle,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Dimension.D200,
            end = Dimension.D400,
            top = Dimension.D100,
            bottom = Dimension.D100,
        ),
        horizontalSpacing = Dimension.D200,
    ) {
        XpBadge(fraction = 1f, size = 16.dp)
        Text(
            text = stringResource(Res.string.ui_level_chip_label, level),
            typography = AppTheme.typography.Label.L500,
            color = AppTheme.colors.accentSecondary,
        )
    }
}

/**
 * A tappable badge/title chip on a Player Card — emoji + name on the accent
 * tint, opening [onClick] to read about it. Unifies the old separate
 * permanent-badge glyph and title pill into one shape.
 */
@Composable
private fun BadgeChip(badge: PlayerBadge, onClick: () -> Unit) {
    Surface(
        color = AppTheme.colors.accentPrimarySubtle,
        contentColor = AppTheme.colors.accentPrimary,
        radius = Radii.Round,
        onClick = onClick,
        bounceScale = 0.96f,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Dimension.D400,
            vertical = Dimension.D200,
        ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimension.D200),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = badge.emoji, typography = AppTheme.typography.Body.B500)
            Text(
                text = badge.name,
                typography = AppTheme.typography.Label.L500,
                color = AppTheme.colors.accentPrimary,
            )
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
                badges = listOf(
                    PlayerBadge(productId = "badge_founding_member_1000", emoji = "🏛", name = "Founding Member"),
                    PlayerBadge(productId = "title_pot_magnet", emoji = "🧲", name = "Pot Magnet"),
                ),
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
