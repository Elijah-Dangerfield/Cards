package com.dangerfield.cards.libraries.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.system.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The odometer's contract is bounded text churn (ENG-59): a roll may show at
 * most [ROLLING_NUMBER_STEPS] intermediate values, because every distinct
 * string handed to `Text` is a fresh Skia glyph blob and that path is where
 * the production ANR traces end. [AnimatedNumberStepsTest] proves the
 * quantizer; these prove both roll paths actually go through it, which is the
 * part a call site can quietly stop doing.
 *
 * The formatter is the probe: it runs on every recomposition with whatever
 * value is about to be rendered, so its distinct inputs are exactly the
 * strings the text saw.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34])
class AnimatedNumberTextTest {

    @Test
    fun revealRoll_showsAtMostOneValuePerStep_andLandsOnTheTarget() = runComposeUiTest {
        val rendered = mutableListOf<Long>()
        var revealKey by mutableStateOf<Any?>(null)
        setContent {
            PreviewContent {
                AnimatedNumberText(
                    value = TARGET,
                    typography = AppTheme.typography.Heading.H600,
                    color = AppTheme.colors.content,
                    formatter = { rendered += it; formatThousands(it) },
                    revealFrom = START,
                    revealKey = revealKey,
                )
            }
        }

        runOnIdle {
            rendered.clear()
            revealKey = "back-from-a-game"
        }
        awaitSettledOnTarget(rendered)

        assertRolledInSteps(rendered)
    }

    @Test
    fun liveChange_showsAtMostOneValuePerStep_andLandsOnTheTarget() = runComposeUiTest {
        val rendered = mutableListOf<Long>()
        var value by mutableStateOf(START)
        setContent {
            PreviewContent {
                AnimatedNumberText(
                    value = value,
                    typography = AppTheme.typography.Heading.H600,
                    color = AppTheme.colors.content,
                    formatter = { rendered += it; formatThousands(it) },
                )
            }
        }
        // The mount warm-up window is wall-clock, not frame-clock: changes inside
        // it snap instead of rolling, so let it lapse for real.
        Thread.sleep(MOUNT_SETTLE_MARGIN_MS)

        runOnIdle {
            rendered.clear()
            value = TARGET
        }
        awaitSettledOnTarget(rendered)

        assertRolledInSteps(rendered)
    }

    /**
     * One `waitForIdle` can return in the gap between the tween's last frame
     * and the looper-posted apply notification that publishes its write, so
     * idle and pump a frame until the target has rendered. Bounded, so a roll
     * that genuinely never lands fails in [assertRolledInSteps] rather than
     * hanging here. `RenderedScenario.settle()` pumps repeatedly for the same
     * reason.
     */
    private fun ComposeUiTest.awaitSettledOnTarget(rendered: List<Long>) {
        repeat(SETTLE_PASSES) {
            waitForIdle()
            if (rendered.lastOrNull() == TARGET) return
            mainClock.advanceTimeByFrame()
        }
    }

    /**
     * Every change in the rendered value is a new text blob, so the churn that
     * matters is the number of times the string changed, not how many times
     * the composable ran.
     */
    private fun assertRolledInSteps(rendered: List<Long>) {
        val changes = rendered.zipWithNext().count { (before, after) -> before != after }
        assertEquals(TARGET, rendered.last(), "the roll must settle on the exact target: $rendered")
        assertTrue(changes > 2, "the number should roll, not snap: $rendered")
        // Into the start value, then at most one change per quantized step
        // including the one onto the target. A per-frame write over a 700ms
        // tween would be ~44.
        assertTrue(
            changes <= ROLLING_NUMBER_STEPS + 2,
            "the text changed $changes times over one roll, expected at most " +
                "${ROLLING_NUMBER_STEPS + 2}: $rendered",
        )
    }

    private companion object {
        const val START = 10_000L
        const val TARGET = 12_500L

        /** Past `MOUNT_SETTLE_MS` (300ms) with a margin for a slow runner. */
        const val MOUNT_SETTLE_MARGIN_MS = 450L
        const val SETTLE_PASSES = 10
    }
}
