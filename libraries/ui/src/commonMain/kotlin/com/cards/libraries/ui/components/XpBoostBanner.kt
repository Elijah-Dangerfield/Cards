package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_xp_boost_banner_activate
import cards.libraries.resources.generated.resources.ui_xp_boost_banner_active_subtitle
import cards.libraries.resources.generated.resources.ui_xp_boost_banner_active_title
import cards.libraries.resources.generated.resources.ui_xp_boost_banner_owned_subtitle
import cards.libraries.resources.generated.resources.ui_xp_boost_banner_owned_title
import cards.libraries.resources.generated.resources.ui_xp_boost_banner_remaining
import com.dangerfield.cards.libraries.cards.XP_BOOST_DEFAULT_DURATION_MS
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonAccent
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The XP boost banner on the Profile screen. One component, two states driven by
 * the live window:
 *
 *  - **Active** — a stashed boost is burning: shows "XP Boost active" with a live
 *    `m:ss` countdown and a progress bar draining toward expiry. No activate
 *    button, so the user can't light a second one over a running window (it'd
 *    just stack invisibly); they wait it out.
 *  - **Owned & idle** — the stash is non-empty and nothing is running: shows how
 *    many are ready plus an **Activate** button that lights one.
 *
 * Renders nothing when there's neither a live window nor anything stashed, so the
 * caller can mount it unconditionally and let it appear/disappear on its own.
 *
 * Teal ([AppTheme.colors.accentSecondary]) throughout to match the boost's badge
 * language elsewhere.
 *
 * @param ownedCount how many inactive boosts the player has stashed.
 * @param expiresAtEpochMs the instant the active window lapses, or null if none.
 * @param onActivate lights one stashed boost (only reachable in the idle state).
 */
@Composable
fun XpBoostBanner(
    ownedCount: Int,
    expiresAtEpochMs: Long?,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remainingMs = rememberBoostRemainingMs(expiresAtEpochMs)
    val isActive = remainingMs > 0L
    // Nothing live and nothing stashed → nothing to show.
    if (!isActive && ownedCount <= 0) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AppTheme.colors.accentSecondarySubtle,
        contentColor = AppTheme.colors.content,
        radius = Radii.Card,
        contentPadding = PaddingValues(Dimension.D800),
    ) {
        if (isActive) {
            ActiveBoostContent(remainingMs = remainingMs)
        } else {
            OwnedBoostContent(ownedCount = ownedCount, onActivate = onActivate)
        }
    }
}

@Composable
private fun ActiveBoostContent(remainingMs: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimension.D400)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimension.D400),
        ) {
            Text(text = "⚡", typography = AppTheme.typography.Heading.H700)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.ui_xp_boost_banner_active_title),
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.content,
                )
                Text(
                    text = stringResource(Res.string.ui_xp_boost_banner_active_subtitle),
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.contentSecondary,
                )
            }
            Text(
                text = stringResource(
                    Res.string.ui_xp_boost_banner_remaining,
                    formatBoostCountdown(remainingMs),
                ),
                typography = AppTheme.typography.Body.B500.SemiBold,
                color = AppTheme.colors.accentSecondary,
            )
        }
        // Drain over a standard window. A stacked window can exceed the default
        // duration — LevelProgressBar clamps the fraction so it just reads full.
        LevelProgressBar(
            fraction = remainingMs.toFloat() / XP_BOOST_DEFAULT_DURATION_MS.toFloat(),
            modifier = Modifier.fillMaxWidth(),
            height = 8.dp,
            faceColor = AppTheme.colors.accentSecondary,
            deepColor = AppTheme.colors.accentSecondaryDeep,
        )
    }
}

@Composable
private fun OwnedBoostContent(ownedCount: Int, onActivate: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
    ) {
        Text(text = "⚡", typography = AppTheme.typography.Heading.H700)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.ui_xp_boost_banner_owned_title),
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.content,
            )
            Text(
                text = stringResource(Res.string.ui_xp_boost_banner_owned_subtitle, ownedCount),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
            )
        }
        ButtonPrimary(
            onClick = onActivate,
            accent = ButtonAccent.Secondary,
            size = ButtonSize.Small,
        ) {
            Text(text = stringResource(Res.string.ui_xp_boost_banner_activate))
        }
    }
}

// ---- Previews ----------------------------------------------------------

@Preview
@Composable
private fun XpBoostBannerPreview_Owned() {
    PreviewContent {
        XpBoostBanner(ownedCount = 3, expiresAtEpochMs = null, onActivate = {})
    }
}

@Preview
@Composable
private fun XpBoostBannerPreview_Active() {
    PreviewContent {
        // Inspection mode pins the countdown to 1:30 (see rememberBoostRemainingMs).
        XpBoostBanner(ownedCount = 2, expiresAtEpochMs = 1_000_000L, onActivate = {})
    }
}
