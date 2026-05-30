package com.dangerfield.cards.libraries.ui.components.poker

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.typography.TypographyResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Canonical sizes for playing-card rendering. Custom sizes are fine but pick
 * one of these whenever possible so the look stays consistent across screens
 * (table, showdown, cheat sheet).
 */
data class PlayingCardSize(val width: Dp, val height: Dp) {
    companion object {
        /** Smallest readable card. Cheat-sheet examples and seat indicators. */
        val Mini = PlayingCardSize(30.dp, 40.dp)

        /** A deck-stack card. */
        val Deck = PlayingCardSize(36.dp, 50.dp)

        /** Community board card. */
        val Board = PlayingCardSize(92.dp, 126.dp)

        /** A player's own hole card. */
        val Hole = PlayingCardSize(104.dp, 142.dp)
    }
}

/**
 * A face-up playing card. Rank in the top-left, suit symbol in the bottom-left
 * (poker convention so it's readable when cards overlap from the right).
 */
@Composable
fun PlayingCard(
    card: Card,
    size: PlayingCardSize,
    modifier: Modifier = Modifier,
    rankTypography: TypographyResource? = null,
    suitTypography: TypographyResource? = null,
) {
    val width = size.width
    val height = size.height
    val isRed = card.suit == Suit.Hearts || card.suit == Suit.Diamonds
    val resolvedRankType = rankTypography ?: defaultRankTypography(width)
    val resolvedSuitType = suitTypography ?: defaultSuitTypography(width)
    val color = if (isRed) AppTheme.colors.danger else AppTheme.colors.background
    val cornerRadius = cornerRadiusFor(width)
    val padding = paddingFor(width)
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .shadow(shadowElevation(width), RoundedCornerShape(cornerRadius))
            .clip(RoundedCornerShape(cornerRadius))
            .background(AppTheme.colors.poker.cardWhite.color)
            .padding(horizontal = padding, vertical = padding),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = card.rank.display,
                typography = resolvedRankType,
                color = color,
                textAlign = TextAlign.Start,
            )
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                Text(
                    text = card.suit.symbol,
                    typography = resolvedSuitType,
                    color = color,
                )
            }
        }
    }
}

/**
 * A face-down card — back of deck. Used for community cards not yet
 * dealt and opponent hole cards.
 *
 * Style is read from the ambient [LocalCardBackStyle] (default = the
 * stock blue back). Set once at the play-screen root via
 * [androidx.compose.runtime.CompositionLocalProvider] so every face-down
 * card in the composition picks up the equipped style automatically.
 *
 * Solid background by design — the back must obscure whatever's behind
 * it (table felt, slot well) regardless of style.
 */
@Composable
fun PlayingCardBack(
    size: PlayingCardSize,
    modifier: Modifier = Modifier,
    style: CardBackStyle = LocalCardBackStyle.current,
    avatarOverlay: AvatarBackOverlay? = null,
) {
    // [Avatar] is personal: when the caller didn't pass an overlay (e.g.
    // opponent hole cards, deck stack, dealt-but-not-revealed community
    // cards), fall back to the default look so the human's avatar
    // doesn't leak onto every face-down card.
    val effectiveStyle = if (style == CardBackStyle.Avatar && avatarOverlay == null) {
        CardBackStyle.Default
    } else {
        style
    }
    val cornerRadius = cornerRadiusFor(size.width)
    val palette = paletteFor(effectiveStyle)
    Box(
        modifier = modifier
            .size(width = size.width, height = size.height)
            .shadow(shadowElevation(size.width) - 1.dp, RoundedCornerShape(cornerRadius))
            .clip(RoundedCornerShape(cornerRadius))
            .background(palette.baseBrush)
            .border(1.dp, palette.borderColor, RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        CardBackOrnament(style = effectiveStyle, size = size, avatarOverlay = avatarOverlay)
    }
}

/**
 * Per-style overlays drawn on top of the base gradient — skull/star/
 * holographic shimmer / avatar emoji etc. Solid-gradient styles
 * (Default, Marble, Gold, Neon, Diamond, ComebackKid, Fire) draw nothing
 * extra so their palette alone defines the look.
 */
@Composable
private fun CardBackOrnament(
    style: CardBackStyle,
    size: PlayingCardSize,
    avatarOverlay: AvatarBackOverlay?,
) {
    when (style) {
        CardBackStyle.Skulls -> {
            // Single centered skull glyph sized to ~55% of the card width.
            // Keeps the read clean at every PlayingCardSize without
            // needing a per-size layout.
            Text(
                text = "💀",
                typography = skullTypographyFor(size.width),
                color = AppTheme.colors.content,
            )
        }
        CardBackStyle.Galaxy -> {
            // Centered sparkle on the indigo-magenta field. The gradient
            // alone reads too "smooth jazz wallpaper"; one glyph hit
            // anchors it as a cosmos card.
            Text(
                text = "✦",
                typography = skullTypographyFor(size.width),
                color = AppTheme.colors.content,
            )
        }
        CardBackStyle.Holographic -> {
            // Slowly cycling hue strip overlay — gives a "shifting"
            // shimmer over the base rainbow. Slow on purpose; a fast
            // rainbow strobing at 60fps is a battery hit and visually
            // noisy at table-density.
            val infinite = rememberInfiniteTransition(label = "holo-shimmer")
            val shift by infinite.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 4_200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "holo-shift",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.55f }
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.35f),
                                Color.Transparent,
                            ),
                            start = androidx.compose.ui.geometry.Offset(shift * size.width.value * 3f, 0f),
                            end = androidx.compose.ui.geometry.Offset(
                                shift * size.width.value * 3f + size.width.value,
                                size.height.value,
                            ),
                        ),
                    ),
            )
        }
        CardBackStyle.Avatar -> {
            if (avatarOverlay != null) {
                val bg = parseHexColor(avatarOverlay.backgroundColorHex)
                    ?: AppTheme.colors.poker.cardBackBlue.color
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = avatarOverlay.emoji,
                        typography = skullTypographyFor(size.width),
                    )
                }
            }
        }
        CardBackStyle.Lattice -> LatticePattern(
            lineColor = Color.White.copy(alpha = 0.65f),
            spacingDp = 11.dp,
            strokeWidthDp = 1.dp,
        )
        CardBackStyle.Hatching -> HatchingPattern(
            lineColor = Color(0xFFC9A24A),
            spacingDp = 5.dp,
            strokeWidthDp = 1.2.dp,
        )
        CardBackStyle.Crosshatch -> CrosshatchPattern(
            lineColor = Color(0xFFD9C2A0).copy(alpha = 0.55f),
            spacingDp = 9.dp,
            strokeWidthDp = 1.dp,
        )
        CardBackStyle.Dots -> DotsPattern(
            dotColor = Color(0xFFE9DFC4).copy(alpha = 0.85f),
            spacingDp = 12.dp,
            radiusDp = 1.6.dp,
        )
        CardBackStyle.Pinstripes -> PinstripesPattern(
            lineColor = Color(0xFFE5E5EA).copy(alpha = 0.55f),
            spacingDp = 8.dp,
            strokeWidthDp = 0.8.dp,
        )
        else -> Unit
    }
}

// ── Canvas pattern primitives. All clip to the parent rounded card via
// the [PlayingCardBack] Box's `.clip(...)`, and inset a couple of dp so
// the pattern doesn't crowd the 1dp border.

@Composable
private fun LatticePattern(
    lineColor: Color,
    spacingDp: Dp,
    strokeWidthDp: Dp,
) {
    val density = LocalDensity.current
    Canvas(modifier = Modifier.fillMaxSize().padding(3.dp)) {
        val spacing = with(density) { spacingDp.toPx() }
        val stroke = with(density) { strokeWidthDp.toPx() }
        val w = size.width
        val h = size.height
        var x = -h
        while (x < w + h) {
            // "/" diagonal (bottom-left → top-right)
            drawLine(
                color = lineColor,
                start = Offset(x, h),
                end = Offset(x + h, 0f),
                strokeWidth = stroke,
            )
            // "\" diagonal (top-left → bottom-right) — together they
            // intersect to form the diamond lattice.
            drawLine(
                color = lineColor,
                start = Offset(x, 0f),
                end = Offset(x + h, h),
                strokeWidth = stroke,
            )
            x += spacing
        }
    }
}

@Composable
private fun HatchingPattern(
    lineColor: Color,
    spacingDp: Dp,
    strokeWidthDp: Dp,
) {
    val density = LocalDensity.current
    Canvas(modifier = Modifier.fillMaxSize().padding(3.dp)) {
        val spacing = with(density) { spacingDp.toPx() }
        val stroke = with(density) { strokeWidthDp.toPx() }
        val w = size.width
        val h = size.height
        var x = -h
        while (x < w + h) {
            drawLine(
                color = lineColor,
                start = Offset(x, h),
                end = Offset(x + h, 0f),
                strokeWidth = stroke,
            )
            x += spacing
        }
    }
}

@Composable
private fun CrosshatchPattern(
    lineColor: Color,
    spacingDp: Dp,
    strokeWidthDp: Dp,
) {
    val density = LocalDensity.current
    Canvas(modifier = Modifier.fillMaxSize().padding(3.dp)) {
        val spacing = with(density) { spacingDp.toPx() }
        val stroke = with(density) { strokeWidthDp.toPx() }
        val w = size.width
        val h = size.height
        var y = spacing
        while (y < h) {
            drawLine(
                color = lineColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = stroke,
            )
            y += spacing
        }
        var x = spacing
        while (x < w) {
            drawLine(
                color = lineColor,
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = stroke,
            )
            x += spacing
        }
    }
}

@Composable
private fun DotsPattern(
    dotColor: Color,
    spacingDp: Dp,
    radiusDp: Dp,
) {
    val density = LocalDensity.current
    Canvas(modifier = Modifier.fillMaxSize().padding(5.dp)) {
        val spacing = with(density) { spacingDp.toPx() }
        val radius = with(density) { radiusDp.toPx() }
        val w = size.width
        val h = size.height
        var y = spacing / 2f
        var row = 0
        while (y < h) {
            // Offset every other row by half a step so the dots don't
            // line up in vertical columns — reads more like a textile
            // pattern than a spreadsheet grid.
            val xStart = if (row % 2 == 0) spacing / 2f else spacing
            var x = xStart
            while (x < w) {
                drawCircle(
                    color = dotColor,
                    radius = radius,
                    center = Offset(x, y),
                )
                x += spacing
            }
            y += spacing * 0.85f
            row++
        }
    }
}

@Composable
private fun PinstripesPattern(
    lineColor: Color,
    spacingDp: Dp,
    strokeWidthDp: Dp,
) {
    val density = LocalDensity.current
    Canvas(modifier = Modifier.fillMaxSize().padding(3.dp)) {
        val spacing = with(density) { spacingDp.toPx() }
        val stroke = with(density) { strokeWidthDp.toPx() }
        val w = size.width
        val h = size.height
        var x = spacing / 2f
        while (x < w) {
            drawLine(
                color = lineColor,
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = stroke,
            )
            x += spacing
        }
    }
}

/**
 * Overlay payload for [CardBackStyle.Avatar]. Pass non-null only at the
 * render sites that represent the local human's seat — opponent /
 * deck / community card-back render sites should pass null so we fall
 * back to a neutral style instead of stamping the user's emoji onto
 * cards that aren't theirs.
 */
data class AvatarBackOverlay(
    val emoji: String,
    val backgroundColorHex: String?,
)

@Composable
private fun skullTypographyFor(width: Dp): TypographyResource = when {
    width >= 90.dp -> AppTheme.typography.Heading.H1000
    width >= 60.dp -> AppTheme.typography.Heading.H800
    width >= 40.dp -> AppTheme.typography.Body.B700
    else -> AppTheme.typography.Body.B600
}

/**
 * Lenient `#RRGGBB`/`#AARRGGBB` parser. Returns null on garbage so
 * callers fall back to a safe default instead of crashing on a typo
 * shipped from the server-side avatar catalog.
 */
private fun parseHexColor(hex: String?): Color? {
    if (hex == null) return null
    val trimmed = hex.removePrefix("#")
    val v = trimmed.toLongOrNull(16) ?: return null
    return when (trimmed.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)
        else -> null
    }
}

/** An empty placeholder slot. Pairs with [PlayingCard] and [PlayingCardBack] for not-yet-dealt positions. */
@Composable
fun PlayingCardSlot(
    size: PlayingCardSize,
    modifier: Modifier = Modifier,
) {
    val cornerRadius = cornerRadiusFor(size.width)
    Box(
        modifier = modifier
            .size(width = size.width, height = size.height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(AppTheme.colors.poker.cardSlot.color),
    )
}

// ----- Internal style helpers -----------------------------------------------

@Composable
private fun defaultRankTypography(width: Dp): TypographyResource = when {
    width >= 90.dp -> AppTheme.typography.Heading.H1000
    width >= 60.dp -> AppTheme.typography.Heading.H800
    width >= 40.dp -> AppTheme.typography.Body.B700
    else -> AppTheme.typography.Body.B600
}

@Composable
private fun defaultSuitTypography(width: Dp): TypographyResource = when {
    width >= 90.dp -> AppTheme.typography.Heading.H1000
    width >= 60.dp -> AppTheme.typography.Body.B600
    else -> AppTheme.typography.Body.B500
}

private fun cornerRadiusFor(width: Dp): Dp = when {
    width >= 70.dp -> 12.dp
    width >= 40.dp -> 8.dp
    else -> 6.dp
}

private fun paddingFor(width: Dp): Dp = when {
    width >= 70.dp -> 8.dp
    width >= 40.dp -> 4.dp
    else -> 3.dp
}

private fun shadowElevation(width: Dp): Dp = when {
    width >= 70.dp -> 6.dp
    width >= 40.dp -> 4.dp
    else -> 2.dp
}

// ----- Previews -------------------------------------------------------------

@Preview
@Composable
private fun PlayingCardPreview_AllSizes() {
    PreviewContent {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PlayingCard(Card(Rank.Ace, Suit.Spades), PlayingCardSize.Mini)
                PlayingCard(Card(Rank.King, Suit.Hearts), PlayingCardSize.Mini)
                PlayingCard(Card(Rank.Ten, Suit.Diamonds), PlayingCardSize.Mini)
                PlayingCardBack(PlayingCardSize.Mini)
                PlayingCardSlot(PlayingCardSize.Mini)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PlayingCard(Card(Rank.Queen, Suit.Clubs), PlayingCardSize.Deck)
                PlayingCardBack(PlayingCardSize.Deck)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PlayingCard(Card(Rank.Ace, Suit.Hearts), PlayingCardSize.Board)
                PlayingCardBack(PlayingCardSize.Board)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PlayingCard(Card(Rank.Jack, Suit.Spades), PlayingCardSize.Hole)
                PlayingCard(Card(Rank.Jack, Suit.Hearts), PlayingCardSize.Hole)
            }
        }
    }
}
