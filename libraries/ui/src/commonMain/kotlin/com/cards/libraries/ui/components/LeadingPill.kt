package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Header-shaped pill: a small leading element (~18dp footprint) + a
 * short trailing label, on a rounded `surfaceSecondary` background.
 * Shared chrome behind [ChipBadge], [LevelPill], and any future
 * progression-family badge — keeps the three the same height + same
 * padding without three separate copies of the recipe.
 *
 * Sizing recipe (pinned for the family):
 *  - horizontal padding 10dp, vertical 6dp
 *  - 8dp spacer between leading and trailing
 *
 * The [trailing] slot is a `@Composable`, not a plain string, so
 * callers that want animated values (e.g. `AnimatedNumberText` for a
 * live chip balance) can pass them directly. The [text] overload is
 * the shorthand for the common static-string case.
 *
 * Callers should give [leading] a fixed-footprint composable (e.g. an
 * 18dp circle / icon / ring) so adjacent pills line up visually.
 */
@Composable
fun LeadingPill(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(Radii.Round.shape)
            .background(AppTheme.colors.surfaceSecondary.color)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = Dimension.D400, vertical = Dimension.D400),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(modifier = Modifier.width(8.dp))
        trailing()
    }
}

/**
 * Static-string shorthand for the common case. Renders the trailing
 * label as DS [Text] at the family's default typography (Body.B500).
 */
@Composable
fun LeadingPill(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leading: @Composable () -> Unit,
) {
    LeadingPill(
        modifier = modifier,
        onClick = onClick,
        leading = leading,
        trailing = {
            Text(
                text = text,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.text,
            )
        },
    )
}

@Preview
@Composable
private fun LeadingPillPreview_StaticText() {
    PreviewContent {
        LeadingPill(text = "Hello", leading = { ChipCoin() })
    }
}
