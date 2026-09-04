package com.dangerfield.cards.libraries.ui.components

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * An endlessly repeating float that **stops existing** under `@Preview` and in
 * screenshot tests, where it holds [previewValue] instead.
 *
 * Use this rather than `rememberInfiniteTransition().animateFloat(...)` directly.
 * Two things go wrong with the raw version, and neither is obvious:
 *
 * 1. **It hangs any screenshot capture.** A running animation asks for another
 *    frame at the end of every frame, forever, so Compose never goes idle and a
 *    capture that waits for idle spins at 100% CPU producing nothing. It does
 *    not time out and it does not fail — it just never finishes, which reads as
 *    a hung build rather than a bad test.
 * 2. **It makes goldens impossible anyway.** Even given a capture that did
 *    return, it would sample the animation at whatever phase it happened to
 *    reach, so the image would differ run to run and every comparison would be
 *    a false positive.
 *
 * Returning `State` rather than a `Float` is the other half of the contract, and
 * the reason this returns what it does: read `.value` inside a `graphicsLayer`,
 * `drawBehind` or `Canvas` lambda so the animation only invalidates *draw*.
 * Unwrapping it with `by` at the call site subscribes composition to a value
 * that changes every frame, which is what wedged the RenderThread in ENG-49 and
 * is what `AnimatedStateReadInComposition` now fails the build over.
 *
 * [previewValue] defaults to [targetValue] — the end of the animation, which is
 * usually its most visible state and so the most useful thing to show in a
 * preview. Pass something else when the resting state reads better.
 */
@Composable
fun rememberLoopingFloat(
    initialValue: Float,
    targetValue: Float,
    animationSpec: InfiniteRepeatableSpec<Float>,
    label: String = "looping-float",
    previewValue: Float = targetValue,
): State<Float> {
    if (LocalInspectionMode.current) {
        return remember(previewValue) { mutableStateOf(previewValue) }
    }
    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(
        initialValue = initialValue,
        targetValue = targetValue,
        animationSpec = animationSpec,
        label = label,
    )
}
