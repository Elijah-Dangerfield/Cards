package com.dangerfield.cards.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyDelegate

/**
 * > **Runs since detekt `2.0.0-alpha.6`.** For a while it did not, and the cause
 * > is worth keeping: on `2.0.0-alpha.5` this rule was registered in
 * > [CardsRuleSetProvider], configured active, and compiled into the ruleset jar,
 * > and detekt simply never dispatched to it. The build passed and detekt
 * > reported success the whole time, which is exactly what a working rule that
 * > finds nothing looks like. Rule order, config-cache staleness, jar freshness,
 * > YAML shape and the baseline were each ruled out first; the version alone was
 * > the cause, and `alpha.6` made it dispatch with no change to the logic below.
 * >
 * > It then found **19 violations on its first real run**, seven of them in files
 * > that had just been swept by hand for this exact pattern. See ENG-54.
 * >
 * > The lesson generalises: if a custom detekt rule appears to do nothing,
 * > suspect the detekt version before the rule, and verify by making it report
 * > unconditionally rather than by trusting a clean run.
 *
 * Flags `val x by animateFloatAsState(...)`, and its siblings, inside a
 * composable.
 *
 * The `by` delegate unwraps the `State<T>` **during composition**, which
 * subscribes the enclosing composable to a value that changes every animation
 * frame. Everything in that scope then recomposes at 60fps for as long as the
 * animation runs, whether or not anything it draws actually changed.
 *
 * This is not theoretical. It is ENG-49, and it cost four production ANRs:
 * `PlayerArea` read a pulsing alpha this way, so the player's name, chip count
 * and hand label recomposed 471 times in a 25-second trace. Rebuilding that
 * text every frame thrashed Skia's glyph cache and wedged the RenderThread hard
 * enough that anything else needing it — opening a dialog, closing one, drawing
 * an ordinary frame — hung past the ANR threshold. Fixing it dropped
 * `PlayerArea` from 471 recompositions to 6.
 *
 * It also hid a second instance of itself: `HoleCardSlot` had the same bug in
 * three places, invisible until the first was fixed. That is what this rule is
 * for — the instance nobody has found yet.
 *
 * **The fix** is to keep the `State` and read it where it is cheapest:
 *
 * ```kotlin
 * // Recomposes every frame:
 * val alpha by animateFloatAsState(target)
 * Modifier.graphicsLayer { this.alpha = alpha }
 *
 * // Recomposes never; the draw phase alone invalidates:
 * val alpha = animateFloatAsState(target)
 * Modifier.graphicsLayer { this.alpha = alpha.value }
 * ```
 *
 * **When composition genuinely needs the value** — usually because it decides
 * which composable to emit — derive the narrower thing it actually needs, so
 * you recompose on that instead of on every frame:
 *
 * ```kotlin
 * val showingFace by remember { derivedStateOf { rotation.value <= 90f } }
 * ```
 *
 * If neither applies, `@Suppress("AnimatedStateReadInComposition")` with a
 * comment saying why. A per-frame recomposition that is genuinely required is
 * fine; one nobody noticed is what causes ANRs.
 */
class AnimatedStateReadInComposition(config: Config) : Rule(
    config,
    "Animated state unwrapped with `by` during composition recomposes its scope every frame; " +
        "keep the State and read it in a draw/layout lambda instead.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression?.text ?: return
        if (callee !in ANIMATION_PRODUCERS) return
        // Only the `by` form is a problem. `val x = animateFloatAsState(...)`
        // keeps the State and is exactly the fix this rule asks for.
        val property = expression.delegatedProperty() ?: return
        report(
            Finding(
                Entity.from(expression),
                "`${property.name} by $callee(...)` unwraps animated state during composition, so " +
                    "everything in this scope recomposes on every animation frame. Drop the `by`, " +
                    "keep the State, and read `.value` inside the graphicsLayer/drawBehind lambda " +
                    "that uses it. If composition truly needs it (it picks which composable to " +
                    "emit), wrap the narrower condition in `derivedStateOf`. See ENG-49.",
            ),
        )
    }

    /**
     * The property this call is the `by` delegate of, or null when it isn't one.
     *
     * Detected from the call rather than by overriding `visitProperty`, which
     * detekt does not dispatch to rules here — `VerifyStrings` uses the same
     * call-expression entry point. Two shapes reach a delegate: the bare call
     * (`by animateFloatAsState(...)`), whose parent is the delegate directly,
     * and the receiver call (`by transition.animateFloat(...)`), which is
     * wrapped in a dot-qualified expression first.
     */
    private fun KtCallExpression.delegatedProperty(): KtProperty? {
        val delegate = when (val parent = parent) {
            is KtPropertyDelegate -> parent
            is KtDotQualifiedExpression -> parent.parent as? KtPropertyDelegate
            else -> null
        } ?: return null
        return delegate.parent as? KtProperty
    }

    private companion object {
        /**
         * Everything in `androidx.compose.animation.core` that returns a
         * `State<T>`. Matched by name because this rule runs without type
         * resolution; a same-named function that isn't an animation is a false
         * positive worth the coverage, and is suppressible.
         */
        val ANIMATION_PRODUCERS = setOf(
            // animate*AsState
            "animateFloatAsState",
            "animateIntAsState",
            "animateDpAsState",
            "animateColorAsState",
            "animateSizeAsState",
            "animateOffsetAsState",
            "animateRectAsState",
            "animateIntOffsetAsState",
            "animateIntSizeAsState",
            "animateValueAsState",
            // Transition / InfiniteTransition members
            "animateFloat",
            "animateInt",
            "animateDp",
            "animateColor",
            "animateSize",
            "animateOffset",
            "animateRect",
            "animateIntOffset",
            "animateIntSize",
            "animateValue",
        )
    }
}
