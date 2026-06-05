package com.dangerfield.cards.libraries.ui.components.achievement

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.Achievement
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.bounceClick
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.thenIf
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The one canonical way to render an achievement as a glossy 3D "medal"
 * disc — the achievement equivalent of [com.dangerfield.cards.libraries.ui.components.ChipCoin]
 * or `AvatarCircle`. A domed radial gradient (lit from the top-left) plus a
 * soft specular highlight give it a raised, Apple-Fitness-award feel.
 *
 * It's a real two-sided coin: the **front** carries the icon; the **back**
 * carries the date you earned it (or your progress "X / Y" toward earning it).
 * The back only ever comes into view when the medal is flipped ([flipOnInit]
 * / [interactiveFlip]); grid medals sit on their front and never animate.
 *
 * States:
 *  - **earned** → full rarity color + gloss.
 *  - **locked** (not earned, not mystery) → desaturated grey, icon dimmed.
 *  - **mystery** (not earned + [Achievement.isMystery]) → grey disc with "?".
 *
 * Everything is painted in a single [Canvas] so the medal supports intrinsic
 * measurement — it can sit inside a `FlowRow`/`Row` with `weight` without the
 * SubcomposeLayout intrinsics crash a `BoxWithConstraints` would cause. The
 * disc is always 1:1; pass a square size via [modifier] or a
 * `Modifier.weight(1f)` and it squares itself.
 *
 * [earnedAtEpochMs] (unlock time) and [progress] (count toward
 * [Achievement.criterion] target) feed the back face; pass them whenever the
 * medal can flip. [onClick] uses a scale "bounce" press (no Material ripple).
 *
 * [flipOnInit] plays a one-shot 360° coin-flip on first composition.
 * [interactiveFlip] lets the user grab the medal: a horizontal drag maps
 * straight to [rotationY] so they can spin it and hold it at any angle, a tap
 * does a full flip, and on release it settles to the nearest face like a real
 * coin. Reserved for the focused detail-sheet medal.
 */
@Composable
fun AchievementMedal(
    achievement: Achievement,
    earned: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    earnedAtEpochMs: Long? = null,
    progress: Int = 0,
    flipOnInit: Boolean = false,
    interactiveFlip: Boolean = false,
) {
    // Plain float source of truth for the Y rotation — driven synchronously by
    // the drag and by `animate(...)` for the init spin / tap-flip / settle. A
    // plain float (not an Animatable fed by per-event coroutines) can never
    // land on NaN, which a stray drag delta otherwise could — and `roundToInt`
    // on NaN crashes.
    var rotation by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var animJob by remember { mutableStateOf<Job?>(null) }

    // Which face is up, derived so the Canvas only redraws when the face
    // actually changes (~twice per flip) instead of every rotation frame.
    // The smooth turn itself lives entirely in the graphicsLayer below, which
    // reads `rotation` in its deferred draw lambda — reading `rotation` inside
    // the Canvas draw scope instead would re-run text measuring + brush
    // allocation 60×/sec and is what made the detail sheet feel laggy.
    val showingBack by remember {
        derivedStateOf {
            val angle = ((rotation % 360f) + 360f) % 360f
            angle > 90f && angle < 270f
        }
    }

    LaunchedEffect(Unit) {
        if (flipOnInit) {
            animJob = scope.launch {
                animate(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
                ) { value, _ -> rotation = value }
            }
        }
    }

    val isMystery = achievement.isMystery && !earned
    val rarity = achievement.rarity.toAccentColor()

    val highlight: Color
    val mid: Color
    val edge: Color
    if (earned) {
        highlight = lerp(rarity, Color.White, 0.50f)
        mid = rarity
        edge = lerp(rarity, Color.Black, 0.42f)
    } else {
        highlight = AppTheme.colors.surfaceHigh.color
        mid = AppTheme.colors.surfaceRaised.color
        edge = lerp(AppTheme.colors.surfaceRaised.color, Color.Black, 0.30f)
    }
    val gloss = Color.White.copy(alpha = if (earned) 0.38f else 0.12f)
    val rim = Color.White.copy(alpha = if (earned) 0.16f else 0.08f)
    val glyph = if (isMystery) "?" else achievement.icon
    val glyphColor = if (isMystery) AppTheme.colors.contentSecondary.color else Color.Black
    val glyphAlpha = if (earned || isMystery) 1f else 0.45f
    // Engraved-reverse text + its ink (dark on a gold face, light on grey).
    val backText = remember(earned, earnedAtEpochMs, progress, achievement.id) {
        backFaceText(
            earned = earned,
            earnedAtEpochMs = earnedAtEpochMs,
            progress = progress,
            target = achievement.criterion.target,
            isMystery = achievement.isMystery,
        )
    }
    val backInk = if (earned) lerp(rarity, Color.Black, 0.62f) else Color.White.copy(alpha = 0.88f)

    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer {
                rotationY = rotation
                // Lower cameraDistance = stronger perspective, so the flip
                // reads as a coin turning through space (depth) rather than a
                // flat horizontal squash.
                cameraDistance = 9f * density
            }
            .clip(CircleShape)
            .thenIf(interactiveFlip) {
                pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { animJob?.cancel() },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            if (dragAmount.isFinite()) rotation += dragAmount * DegreesPerPixel
                        },
                        onDragEnd = {
                            // Settle to the nearest full turn — push past
                            // halfway and it completes; let go early and it
                            // falls back, like flipping a real coin.
                            val rest = (rotation / 360f).roundToInt() * 360f
                            animJob = scope.launch {
                                animate(
                                    initialValue = rotation,
                                    targetValue = rest.toFloat(),
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessLow,
                                    ),
                                ) { value, _ -> rotation = value }
                            }
                        },
                    )
                }.pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            // Flip to the *other* face and settle there, so a
                            // tap actually shows the reverse instead of a full
                            // turn back to the same side.
                            val target = (rotation / 180f).roundToInt() * 180f + 180f
                            animJob = scope.launch {
                                animate(
                                    initialValue = rotation,
                                    targetValue = target.toFloat(),
                                    animationSpec = tween(600, easing = FastOutSlowInEasing),
                                ) { value, _ -> rotation = value }
                            }
                        },
                    )
                }
            }
            .thenIf(!interactiveFlip && onClick != null) { bounceClick(onClick = onClick!!) },
    ) {
        val r = size.minDimension / 2f
        val c = center

        // Coin material — same domed body, gloss, and rim on both faces.
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(0f to highlight, 0.55f to mid, 1f to edge),
                center = Offset(c.x - r * 0.28f, c.y - r * 0.32f),
                radius = r * 1.35f,
            ),
            radius = r,
            center = c,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(0f to gloss, 1f to Color.Transparent),
                center = Offset(c.x - r * 0.32f, c.y - r * 0.42f),
                radius = r * 0.85f,
            ),
            radius = r,
            center = c,
        )
        val rimW = r * 0.05f
        drawCircle(color = rim, radius = r - rimW / 2f, style = Stroke(width = rimW))

        if (!showingBack) {
            // Front: the icon.
            val measured = textMeasurer.measure(
                text = glyph,
                style = TextStyle(fontSize = (r * 0.9f).toSp(), color = glyphColor),
            )
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(c.x - measured.size.width / 2f, c.y - measured.size.height / 2f),
                alpha = glyphAlpha,
            )
        } else {
            // Back: the layer's rotationY mirrors everything past 90°, so we
            // pre-mirror the engraved text horizontally to cancel it out and
            // keep it readable.
            scale(scaleX = -1f, scaleY = 1f, pivot = c) {
                val measured = textMeasurer.measure(
                    text = backText,
                    style = TextStyle(
                        fontSize = (r * 0.34f).toSp(),
                        color = backInk,
                        textAlign = TextAlign.Center,
                    ),
                )
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(
                        c.x - measured.size.width / 2f,
                        c.y - measured.size.height / 2f,
                    ),
                )
            }
        }
    }
}

/**
 * What the coin's reverse reads: the earned date once earned, your "X / Y"
 * progress while it's a chase goal, or a lock when there's nothing to count.
 * Mystery achievements stay a secret on the back too.
 */
private fun backFaceText(
    earned: Boolean,
    earnedAtEpochMs: Long?,
    progress: Int,
    target: Int,
    isMystery: Boolean,
): String = when {
    earned && earnedAtEpochMs != null -> {
        val dt = kotlin.time.Instant
            .fromEpochMilliseconds(earnedAtEpochMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        "${MonthAbbrev[dt.month.ordinal]} ${dt.day}\n${dt.year}"
    }
    earned -> "✓"
    isMystery -> "?"
    target > 1 -> "$progress\n/ $target"
    else -> "🔒"
}

private val MonthAbbrev = listOf(
    "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
    "JUL", "AUG", "SEP", "OCT", "NOV", "DEC",
)

/** Drag sensitivity: degrees of Y-rotation per pixel of horizontal drag.
 *  ~0.7 means a brisk swipe across a 120dp medal carries it past halfway so
 *  the flip completes. */
private const val DegreesPerPixel = 0.7f

@Preview
@Composable
private fun AchievementMedalPreview_States() {
    PreviewContent {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            val earnedAch = com.dangerfield.cards.libraries.cards.AllAchievements.first { !it.isMystery }
            val mysteryAch = com.dangerfield.cards.libraries.cards.AllAchievements.first { it.isMystery }
            AchievementMedal(achievement = earnedAch, earned = true, modifier = Modifier.size(72.dp))
            AchievementMedal(achievement = earnedAch, earned = false, modifier = Modifier.size(72.dp))
            AchievementMedal(achievement = mysteryAch, earned = false, modifier = Modifier.size(72.dp))
        }
    }
}
