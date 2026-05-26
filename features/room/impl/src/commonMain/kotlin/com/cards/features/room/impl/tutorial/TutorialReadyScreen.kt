package com.dangerfield.cards.features.room.impl.tutorial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Final tutorial page. Visual hero: two slightly-overlapping aces
 * sitting on a soft radial glow, sparkle accents scattered around
 * them. Below the hero: italic amber "You're ready." headline, body
 * text, and a Done CTA.
 *
 * Achievement reveal does NOT render here. On first-time completion
 * the entry point navigates to [AchievementUnlockedRoute] which
 * stacks above this screen as a floating-window dialog. On replay,
 * no navigation fires and the user sees only this page.
 */
@Composable
internal fun TutorialReadyScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Screen(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hero in the upper half. The glow + sparkles + cards
            // share a single Box so the sparkle positions are stable
            // relative to the cards as the screen height changes.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                ReadyHero()
            }
            Text(
                text = "You're ready.",
                typography = AppTheme.typography.Display.D1100.Italic,
                color = ColorResource.Amber500,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            VerticalSpacerD500()
            Text(
                text = "Raise the strong hands. Call when the price is right. Fold the rest. The bots are waiting in Practice.",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            VerticalSpacerD800()
            ButtonPrimary(
                onClick = onDone,
                size = ButtonSize.Medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }
            VerticalSpacerD800()
        }
    }
}

@Composable
private fun ReadyHero() {
    Box(
        modifier = Modifier.size(width = 240.dp, height = 280.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Soft radial glow behind the cards. Anchors the eye, lifts
        // the cards off the dark background, and reinforces the
        // "you made it" warmth without needing literal rays.
        GoldGlow(modifier = Modifier.fillMaxSize())

        // A sprinkling of sparkles around the cards. Hand-placed
        // positions; randomization would feel busy at this scale.
        Sparkle(modifier = Modifier.offset(x = 80.dp, y = (-90).dp), size = 18.dp)
        Sparkle(modifier = Modifier.offset(x = (-90).dp, y = 60.dp), size = 14.dp)
        Sparkle(modifier = Modifier.offset(x = 100.dp, y = 80.dp), size = 12.dp)

        // Two aces, slightly fanned. The back card tilts left, the
        // front card sits straight. Hearts in front so the red pops
        // against the gold glow.
        PlayingCard(
            card = Card(Rank.Ace, Suit.Spades),
            size = PlayingCardSize.Hole,
            modifier = Modifier
                .offset(x = (-18).dp, y = 4.dp)
                .graphicsLayer { rotationZ = -8f },
        )
        PlayingCard(
            card = Card(Rank.Ace, Suit.Hearts),
            size = PlayingCardSize.Hole,
            modifier = Modifier
                .offset(x = 18.dp, y = (-4).dp)
                .graphicsLayer { rotationZ = 4f },
        )
    }
}

/**
 * Soft amber radial glow rendered behind the cards. Falls off to
 * fully transparent at the edges so it blends into the dark
 * background instead of forming a hard circle.
 */
@Composable
private fun GoldGlow(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFFE5B946).copy(alpha = 0.35f),
                Color(0xFFE5B946).copy(alpha = 0.08f),
                Color.Transparent,
            ),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = size.minDimension * 0.6f,
        )
        drawRect(brush = brush)
    }
}

/**
 * Four-point amber sparkle (a "+" with thin diagonals). Drawn on a
 * Canvas so the asterisk shape stays crisp at any size without
 * pulling in font glyphs that render differently per platform.
 */
@Composable
private fun Sparkle(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 14.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val color = Color(0xFFE5B946)
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val armLong = this.size.minDimension * 0.5f
        val armShort = this.size.minDimension * 0.18f
        val strokeWidth = this.size.minDimension * 0.12f
        // Vertical stroke (long).
        drawLine(
            color = color,
            start = Offset(cx, cy - armLong),
            end = Offset(cx, cy + armLong),
            strokeWidth = strokeWidth,
        )
        // Horizontal stroke (long).
        drawLine(
            color = color,
            start = Offset(cx - armLong, cy),
            end = Offset(cx + armLong, cy),
            strokeWidth = strokeWidth,
        )
        // Diagonal pinpricks (short) — give the sparkle its star feel
        // without making it busy.
        drawLine(
            color = color.copy(alpha = 0.7f),
            start = Offset(cx - armShort, cy - armShort),
            end = Offset(cx + armShort, cy + armShort),
            strokeWidth = strokeWidth * 0.7f,
        )
        drawLine(
            color = color.copy(alpha = 0.7f),
            start = Offset(cx + armShort, cy - armShort),
            end = Offset(cx - armShort, cy + armShort),
            strokeWidth = strokeWidth * 0.7f,
        )
    }
}

@Preview
@Composable
private fun TutorialReadyScreenPreview() {
    PreviewContent {
        TutorialReadyScreen(onDone = {})
    }
}

@Preview
@Composable
private fun ReadyHeroPreview() {
    PreviewContent {
        Box(modifier = Modifier.padding(32.dp), contentAlignment = Alignment.Center) {
            ReadyHero()
        }
    }
}
