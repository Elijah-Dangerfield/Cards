package com.dangerfield.cards.libraries.ui.components.room

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import androidx.compose.ui.tooling.preview.Preview

/**
 * The room visibility tag — a small pill that reads "Public" (teal globe) or
 * "Invite only" (gold lock). Sits in the right slot of a room header so the
 * player always knows which world they're in: public = matchmaker-seated,
 * private = invite-only / host-run.
 *
 * The two kinds are colour-coded to the app's accents (public → teal
 * [Colors.accentSecondary], private → gold [Colors.accentPrimary]) so the tag
 * matches the accent of the screen it sits on.
 */
enum class RoomVisibility { Public, Private }

@Composable
fun VisTag(
    kind: RoomVisibility,
    modifier: Modifier = Modifier,
) {
    val accent: ColorResource = when (kind) {
        RoomVisibility.Public -> AppTheme.colors.accentSecondary
        RoomVisibility.Private -> AppTheme.colors.accentPrimary
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(accent.withAlpha(0.14f).color)
            .border(width = 1.dp, color = accent.withAlpha(0.34f).color, shape = CircleShape)
            .padding(horizontal = Dimension.D400, vertical = Dimension.D100),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimension.D200),
    ) {
        when (kind) {
            RoomVisibility.Public -> GlobeGlyph(accent)
            RoomVisibility.Private -> LockGlyph(accent)
        }
        Text(
            text = if (kind == RoomVisibility.Public) "Public" else "Invite only",
            typography = AppTheme.typography.Label.L400,
            color = accent,
        )
    }
}

@Composable
private fun GlobeGlyph(accent: ColorResource) {
    val color = accent.color
    Canvas(modifier = Modifier.size(13.dp)) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val stroke = Stroke(width = size.minDimension * 0.10f)
        drawCircle(color = color, radius = r * 0.92f, center = c, style = stroke)
        // Equator
        drawLine(color, Offset(c.x - r * 0.92f, c.y), Offset(c.x + r * 0.92f, c.y), strokeWidth = stroke.width)
        // Meridians (two ellipses approximated by thin vertical arcs)
        drawOval(
            color = color,
            topLeft = Offset(c.x - r * 0.45f, c.y - r * 0.92f),
            size = androidx.compose.ui.geometry.Size(r * 0.9f, r * 1.84f),
            style = stroke,
        )
    }
}

@Composable
private fun LockGlyph(accent: ColorResource) {
    val color = accent.color
    Canvas(modifier = Modifier.size(12.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.12f)
        // Body
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.12f, h * 0.42f),
            size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.5f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f, w * 0.12f),
            style = stroke,
        )
        // Shackle
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.28f, h * 0.12f),
            size = androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.5f),
            style = stroke,
        )
    }
}

@Preview
@Composable
private fun VisTagPreview() {
    PreviewContent(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VisTag(kind = RoomVisibility.Public)
            VisTag(kind = RoomVisibility.Private)
        }
    }
}
