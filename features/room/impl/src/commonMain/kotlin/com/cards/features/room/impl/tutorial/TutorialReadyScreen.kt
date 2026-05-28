package com.dangerfield.cards.features.room.impl.tutorial

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Final tutorial page. Visual hero: two aces fanning out from a
 * neutral stack into their final position over a soft amber glow,
 * with sparkle accents twinkling at staggered phases around them.
 * Below the hero: italic amber "You're ready." headline, body text,
 * and a Done CTA.
 *
 * Achievement reveal does NOT render here. The Done callback is
 * what triggers the celebration: on first-time completion the entry
 * point pops the tutorial and navigates to [AchievementUnlockedRoute]
 * (a floating-window dialog) so the celebration is the user's last
 * beat. On replay, Done just exits.
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
    // Cards animate from a centered stack to their final fanned
    // position on first composition. Four Animatables drive the
    // pair (rotation + horizontal offset per card) so the spring
    // can settle each axis independently and the fan feels organic
    // rather than mechanical.
    val cardARotation = remember { Animatable(0f) }
    val cardBRotation = remember { Animatable(0f) }
    val cardAOffsetX = remember { Animatable(0f) }
    val cardBOffsetX = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // Brief hold lets the cards land centered + stacked before
        // they fan out. Without this the fan happens before the
        // screen transition completes and reads as a glitch.
        delay(220)
        launch {
            cardARotation.animateTo(
                targetValue = -8f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
        launch {
            cardBRotation.animateTo(
                targetValue = 6f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
        launch {
            cardAOffsetX.animateTo(
                targetValue = -22f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
        launch {
            cardBOffsetX.animateTo(
                targetValue = 22f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }

    Box(
        modifier = Modifier.size(width = 240.dp, height = 280.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Soft radial glow behind the cards. Anchors the eye, lifts
        // the cards off the dark background, and reinforces the
        // "you made it" warmth without needing literal rays.
        GoldGlow(modifier = Modifier.fillMaxSize())

        // Sparkles around the cards. Each gets a different size and
        // a different phase offset so they twinkle out of sync; a
        // synchronized twinkle reads as a blinking light rather than
        // an ambient shimmer.
        Sparkle(
            modifier = Modifier.offset(x = 80.dp, y = (-90).dp),
            size = 18.dp,
            phaseOffsetMs = 0,
        )
        Sparkle(
            modifier = Modifier.offset(x = (-90).dp, y = 60.dp),
            size = 14.dp,
            phaseOffsetMs = 700,
        )
        Sparkle(
            modifier = Modifier.offset(x = 100.dp, y = 80.dp),
            size = 12.dp,
            phaseOffsetMs = 1400,
        )
        Sparkle(
            modifier = Modifier.offset(x = (-70).dp, y = (-70).dp),
            size = 10.dp,
            phaseOffsetMs = 350,
        )

        // Two aces, fan-out animated. Spades behind, Hearts in front
        // so the red pops against the gold glow.
        PlayingCard(
            card = Card(Rank.Ace, Suit.Spades),
            size = PlayingCardSize.Hole,
            modifier = Modifier.graphicsLayer {
                translationX = cardAOffsetX.value * density
                rotationZ = cardARotation.value
            },
        )
        PlayingCard(
            card = Card(Rank.Ace, Suit.Hearts),
            size = PlayingCardSize.Hole,
            modifier = Modifier.graphicsLayer {
                translationX = cardBOffsetX.value * density
                rotationZ = cardBRotation.value
            },
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
                PokerPalette.SparkleGold.copy(alpha = 0.35f),
                PokerPalette.SparkleGold.copy(alpha = 0.08f),
                Color.Transparent,
            ),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = size.minDimension * 0.6f,
        )
        drawRect(brush = brush)
    }
}

/**
 * Four-point amber sparkle that pulses on a loop. Alpha and scale
 * both ease in and out on a sine curve so the sparkle reads as a
 * twinkle, not a blink. [phaseOffsetMs] staggers the loop start per
 * sparkle so multiple sparkles on screen don't pulse in unison.
 */
@Composable
private fun Sparkle(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 14.dp,
    phaseOffsetMs: Int = 0,
) {
    val transition = rememberInfiniteTransition(label = "sparkle")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(phaseOffsetMs),
        ),
        label = "sparkle-alpha",
    )
    val scale by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(phaseOffsetMs),
        ),
        label = "sparkle-scale",
    )
    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
    ) {
        val color = PokerPalette.SparkleGold
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val armLong = this.size.minDimension * 0.5f
        val armShort = this.size.minDimension * 0.18f
        val strokeWidth = this.size.minDimension * 0.12f
        // Vertical and horizontal long strokes form the "+" body.
        drawLine(
            color = color,
            start = Offset(cx, cy - armLong),
            end = Offset(cx, cy + armLong),
            strokeWidth = strokeWidth,
        )
        drawLine(
            color = color,
            start = Offset(cx - armLong, cy),
            end = Offset(cx + armLong, cy),
            strokeWidth = strokeWidth,
        )
        // Diagonal pinpricks give the sparkle its star feel without
        // making it busy.
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
