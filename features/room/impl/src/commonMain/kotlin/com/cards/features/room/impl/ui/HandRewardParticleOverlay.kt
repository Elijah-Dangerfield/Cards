package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.XpBadge
import kotlin.math.PI
import kotlin.math.sin

/**
 * One "fly a reward to its home" event. Emitted by [PlayPokerScreen] at the
 * moment the deferred-animation gate releases (the hand-result dialog / bot
 * celebration sheet has dismissed and the held XP / stack values are about
 * to animate to their new numbers). [flyXp] / [flyCoin] gate which tokens
 * actually launch — XP is earned almost every hand, but the coin only flies
 * when the human's stack grew (a win). [id] is a monotonic sequence so each
 * burst re-keys its animation state.
 */
internal data class RewardBurst(
    val id: Int,
    val flyXp: Boolean,
    val flyCoin: Boolean,
)

/**
 * Anchor registry for the hand-end reward particles. The XP token flies up
 * to the top-bar [com.dangerfield.cards.libraries.ui.components.LevelPill]
 * and the coin token flies down to the human's chip-stack tile — both of
 * which sit several composables deep from the screen root. Rather than
 * prop-drill `onGloballyPositioned` callbacks through every layer, the two
 * call sites publish their on-screen bounds (in root coordinates) into this
 * shared holder via [LocalTableRewardAnchors], and the overlay reads them
 * back when it fires. Bounds are nullable so a missing anchor (e.g. the
 * tutorial hides the LevelPill) simply skips that token.
 */
internal class TableRewardAnchors {
    var levelPillBounds: Rect? by mutableStateOf(null)
    var chipStackBounds: Rect? by mutableStateOf(null)
}

internal val LocalTableRewardAnchors = staticCompositionLocalOf<TableRewardAnchors?> { null }

/**
 * Full-screen overlay that flies a small XP badge up to the LevelPill and a
 * gold coin down to the chip stack when [burst] is non-null. Polish on top of
 * the existing XP/stack deferral gate: the tokens launch from the spot the
 * dismissed result overlay occupied and arrive as the pill ring fills and the
 * stack odometer rolls, visually tying the released numbers to where they live.
 *
 * Targets come from [anchors] (published by the LevelPill / chip-stack call
 * sites). Anything still unmeasured or absent is skipped; once every launched
 * token lands, [onComplete] fires so the caller can clear its burst state.
 */
@Composable
internal fun HandRewardParticleOverlay(
    burst: RewardBurst?,
    anchors: TableRewardAnchors,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = burst ?: return
    var overlayOrigin by remember(current.id) { mutableStateOf(Offset.Zero) }
    var overlaySize by remember(current.id) { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned {
                overlayOrigin = it.positionInRoot()
                overlaySize = it.size
            },
    ) {
        // Wait for the overlay to be measured before we can convert the
        // root-space anchor bounds into local launch/target coordinates.
        if (overlaySize == IntSize.Zero) return@Box

        val xpTarget = if (current.flyXp) {
            anchors.levelPillBounds?.let { it.center - overlayOrigin }
        } else {
            null
        }
        val coinTarget = if (current.flyCoin) {
            anchors.chipStackBounds?.let { it.center - overlayOrigin }
        } else {
            null
        }

        val tokenCount = (if (xpTarget != null) 1 else 0) + (if (coinTarget != null) 1 else 0)
        if (tokenCount == 0) {
            // Nothing to fly (e.g. anchors never published) — release the
            // caller's burst state so it doesn't get stuck non-null.
            LaunchedEffect(current.id) { onComplete() }
            return@Box
        }

        var completed by remember(current.id) { mutableStateOf(0) }
        LaunchedEffect(current.id, completed) {
            if (completed >= tokenCount) onComplete()
        }

        val density = LocalDensity.current
        // Launch the two tokens from points either side of where the result
        // overlay's content sat (upper-middle of the table) so their paths
        // don't perfectly overlap out of the gate.
        val spreadPx = with(density) { 10.dp.toPx() }
        val bowPx = with(density) { 26.dp.toPx() }
        val origin = Offset(overlaySize.width / 2f, overlaySize.height * 0.42f)

        if (xpTarget != null) {
            FlyingToken(
                id = "xp-${current.id}",
                origin = origin + Offset(-spreadPx, 0f),
                target = xpTarget,
                bowPx = -bowPx,
                onArrive = { completed++ },
            ) {
                XpBadge(fraction = 1f, size = 16.dp)
            }
        }
        if (coinTarget != null) {
            FlyingToken(
                id = "coin-${current.id}",
                origin = origin + Offset(spreadPx, 0f),
                target = coinTarget,
                bowPx = bowPx,
                onArrive = { completed++ },
            ) {
                ChipCoin(size = 16.dp)
            }
        }
    }
}

/**
 * Flies [content] from [origin] to [target] (both in the parent overlay's
 * local pixel space) along a gently bowed path, fading in at launch and
 * shrinking + fading as it lands. A light haptic tick fires on arrival so the
 * reward registers in the hand. Re-keyed by [id] so each burst replays cleanly.
 */
@Composable
private fun FlyingToken(
    id: Any,
    origin: Offset,
    target: Offset,
    bowPx: Float,
    onArrive: () -> Unit,
    content: @Composable () -> Unit,
) {
    val progress = remember(id) { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(id) {
        progress.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onArrive()
    }
    Box(
        modifier = Modifier.graphicsLayer {
            val p = progress.value
            val pos = lerp(origin, target, p)
            // Perpendicular-ish bow: a single sine hump peaking at the midpoint
            // applied on X so the token arcs rather than tracking a dead-straight
            // line. Sign of [bowPx] sends XP and coin tokens bowing apart.
            val bow = sin(p * PI).toFloat() * bowPx
            translationX = pos.x + bow - size.width / 2f
            translationY = pos.y - size.height / 2f

            val grow = (p / 0.2f).coerceIn(0f, 1f)
            val shrink = ((p - 0.8f) / 0.2f).coerceIn(0f, 1f)
            val s = (0.55f + 0.45f * grow) * (1f - 0.25f * shrink)
            scaleX = s
            scaleY = s
            alpha = when {
                p < 0.12f -> p / 0.12f
                p > 0.82f -> (1f - p) / 0.18f
                else -> 1f
            }.coerceIn(0f, 1f)
        },
    ) {
        content()
    }
}
