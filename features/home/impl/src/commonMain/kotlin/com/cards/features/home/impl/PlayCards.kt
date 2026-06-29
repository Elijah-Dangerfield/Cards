package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.StatusPill
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonAccent
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.FeatureCardAccents
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Home's "Play" picker, per the redesign: one large [PlayFeatureCard] hero
 * over a row of two half-width [PlayTileCard]s. Each card is an accent-tinted
 * gradient panel with a card-suit glyph tile; tapping opens the matching
 * bottom sheet (or routes onward). Accent comes from [FeatureCardAccents] so
 * the play options keep a stable colour identity across the app.
 *
 * Home-local (like the old HomeCtaCard) — lift to :libraries:ui if a second
 * screen ever wants the same shape.
 */

/** Vertical accent wash — brighter at the top, settling into the dark bg.
 *  Shared with [TutorialBanner] so the Play cards + the "New here?" banner
 *  read as the same gradient family. */
internal fun playGradient(accent: Color): Brush = Brush.verticalGradient(
    colors = listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.68f)),
)

@Composable
private fun GlyphTile(glyph: String, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(Radii.R600.shape)
            .background(AppTheme.colors.content.color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.content,
        )
    }
}

/**
 * The hero play option (Practice): glyph tile, title + subtitle, and a
 * trailing [cta] button. The whole card is tappable; the button is the
 * matching affordance.
 */
@Composable
internal fun PlayFeatureCard(
    title: String,
    subtitle: String,
    glyph: String,
    accent: Color,
    cta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.R900.shape)
            .background(playGradient(accent))
            .clickable(onClick = onClick)
            .padding(horizontal = Dimension.D700, vertical = Dimension.D900),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
    ) {
        GlyphTile(glyph = glyph, size = 52.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                typography = AppTheme.typography.Heading.H600,
                color = AppTheme.colors.content,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.content.withAlpha(0.78f),
            )
        }
        ButtonSecondary(onClick = onClick, size = ButtonSize.Small) {
            Text(cta)
        }
    }
}

/**
 * A half-width play option (Quick Match, Friend Game): glyph tile at the top,
 * title + short subtitle anchored to the bottom. Pass `Modifier.weight(1f)`
 * from the caller so two sit side by side. An optional [tag] renders a small
 * status pill in the top-right corner — used to mark a not-yet-shipped option
 * as "Coming soon".
 */
@Composable
internal fun PlayTileCard(
    title: String,
    subtitle: String,
    glyph: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(Radii.Card.shape)
                .background(playGradient(accent))
                .clickable(onClick = onClick)
                .padding(Dimension.D700),
        ) {
            GlyphTile(glyph = glyph, size = 48.dp)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = title,
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.content,
            )
            Text(
                text = subtitle,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.content.withAlpha(0.78f),
            )
        }
        tag?.let {
            StatusPill(
                text = it,
                background = AppTheme.colors.surfaceHigh,
                foreground = AppTheme.colors.content,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Dimension.D400),
            )
        }
    }
}

// --------------------------------------------------------------------------
// Rooms-redesign Play section (SPEC §5): a blue "Public rooms" hero with an
// inner "Find a table" CTA, and a gold "Private room" row that opens the
// create/join sheet. Both lean on [playGradient]/[FeatureCardAccents] so they
// stay in the same gradient family as the Practice/Tournament tiles below.
// --------------------------------------------------------------------------

/** White-line glyph drawn on the gradient — needs no icon asset. */
@Composable
private fun GradientGlyphTile(size: Dp, draw: DrawScope.() -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(Radii.R600.shape)
            .background(AppTheme.colors.content.color.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size * 0.52f), onDraw = draw)
    }
}

/**
 * The PUBLIC rooms hero — pick a buy-in, get auto-seated. Blue [info] gradient,
 * globe glyph, and a full-width inverse "Find a table" CTA. Tapping the card or
 * the button both route into the public Find flow.
 */
@Composable
internal fun PublicRoomsCard(
    title: String,
    subtitle: String,
    cta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val line = AppTheme.colors.content.color
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.Card.shape)
            .background(playGradient(FeatureCardAccents.Blue))
            .clickable(onClick = onClick)
            .padding(Dimension.D800),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
        ) {
            GradientGlyphTile(54.dp) { drawGlobe(line) }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    typography = AppTheme.typography.Heading.H800,
                    color = AppTheme.colors.content,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.content.withAlpha(0.78f),
                )
            }
        }
        Spacer(modifier = Modifier.height(Dimension.D600))
        Button(
            onClick = onClick,
            accent = ButtonAccent.Inverse,
            size = ButtonSize.Large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(cta)
        }
    }
}

/**
 * The PRIVATE room row — invite-only, create or join by code. Gold gradient,
 * lock glyph, trailing chevron. Tapping opens the create/join bottom sheet.
 */
@Composable
internal fun PrivateRoomCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val line = AppTheme.colors.content.color
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.Card.shape)
            .background(playGradient(FeatureCardAccents.Gold))
            .clickable(onClick = onClick)
            .padding(horizontal = Dimension.D700, vertical = Dimension.D750),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
    ) {
        GradientGlyphTile(50.dp) { drawLock(line) }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.content,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.content.withAlpha(0.78f),
            )
        }
        Canvas(modifier = Modifier.size(16.dp)) { drawChevron(line) }
    }
}

private fun DrawScope.drawGlobe(color: androidx.compose.ui.graphics.Color) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    val w = size.minDimension * 0.07f
    drawCircle(color, radius = r * 0.96f, center = c, style = Stroke(width = w))
    drawLine(color, Offset(c.x - r * 0.96f, c.y), Offset(c.x + r * 0.96f, c.y), strokeWidth = w)
    drawOval(
        color = color,
        topLeft = Offset(c.x - r * 0.42f, c.y - r * 0.96f),
        size = Size(r * 0.84f, r * 1.92f),
        style = Stroke(width = w),
    )
}

private fun DrawScope.drawLock(color: androidx.compose.ui.graphics.Color) {
    val w = size.width
    val h = size.height
    val s = w * 0.1f
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.16f, h * 0.42f),
        size = Size(w * 0.68f, h * 0.5f),
        cornerRadius = CornerRadius(w * 0.1f, w * 0.1f),
        style = Stroke(width = s),
    )
    drawArc(
        color = color,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(w * 0.3f, h * 0.1f),
        size = Size(w * 0.4f, h * 0.5f),
        style = Stroke(width = s),
    )
}

private fun DrawScope.drawChevron(color: androidx.compose.ui.graphics.Color) {
    val w = size.width
    val h = size.height
    val s = w * 0.16f
    drawLine(color, Offset(w * 0.3f, h * 0.2f), Offset(w * 0.7f, h * 0.5f), strokeWidth = s)
    drawLine(color, Offset(w * 0.7f, h * 0.5f), Offset(w * 0.3f, h * 0.8f), strokeWidth = s)
}

@Preview
@Composable
private fun PlayCardsPreview() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PublicRoomsCard(
                title = "Public rooms",
                subtitle = "Pick your buy-in — we seat you instantly",
                cta = "Find a table",
                onClick = {},
            )
            PrivateRoomCard(
                title = "Private room",
                subtitle = "Play with friends · create or join by code",
                onClick = {},
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayTileCard(
                    title = "Practice",
                    subtitle = "vs bots",
                    glyph = "♠",
                    accent = FeatureCardAccents.Green,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
                PlayTileCard(
                    title = "Tournament",
                    subtitle = "Royal Flush",
                    glyph = "♛",
                    accent = FeatureCardAccents.Magenta,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                    tag = "Soon",
                )
            }
        }
    }
}
