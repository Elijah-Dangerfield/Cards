package com.dangerfield.cards

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Tester shortcut to the feedback form: a swipe in from the **right edge**
 * fires [onTriggered]. The right edge is a low-collision zone on **iOS** (whose
 * back gesture is the left edge), so it's a quick path for testers there without
 * interfering with normal use. It is NOT free on Android: gesture navigation
 * claims both edges for system back, so the caller must not enable this on
 * Android — it would swallow the back swipe (ENG-31). The caller gates it to
 * tester audiences (debug + TestFlight) on non-Android platforms; App Store
 * users keep the Settings entry only, and Android testers use shake-to-debug.
 *
 * No-op when [enabled] is false. It also never consumes pointer events: it
 * only *observes*, arming only when the touch starts within [EDGE_ZONE_DP] of
 * the right edge and then travels predominantly leftward past
 * [TRIGGER_DISTANCE_DP]. Normal taps/scrolls are unaffected.
 *
 * Watches in the **Initial** pass so a vertical scroll container nested under it
 * can't swallow the gesture before this detector sees it (CARDS-Y: the swipe was
 * unreliable inside scroll views because the scroller won the Main-pass deltas
 * first). We still never *consume*, so a vertical drag in the edge zone keeps
 * scrolling — the `abs(dx) > abs(dy)` gate means only a predominantly-leftward
 * travel fires the trigger.
 */
fun Modifier.rightEdgeSwipe(
    enabled: Boolean,
    onTriggered: () -> Unit,
): Modifier = if (!enabled) this else pointerInput(Unit) {
    val edgeZonePx = EDGE_ZONE_DP.dp.toPx()
    val triggerPx = TRIGGER_DISTANCE_DP.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // Arm only for gestures that begin in the right-edge strip.
        if (down.position.x < size.width - edgeZonePx) return@awaitEachGesture
        var dx = 0f
        var dy = 0f
        while (true) {
            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.changedToUp()) break
            val delta = change.positionChange()
            dx += delta.x
            dy += delta.y
            // Predominantly-leftward drag past the threshold → open feedback.
            if (dx <= -triggerPx && abs(dx) > abs(dy)) {
                onTriggered()
                break
            }
        }
    }
}

private const val EDGE_ZONE_DP = 24
private const val TRIGGER_DISTANCE_DP = 72
