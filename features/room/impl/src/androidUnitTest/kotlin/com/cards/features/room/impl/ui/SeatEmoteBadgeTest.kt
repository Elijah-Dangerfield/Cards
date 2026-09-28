package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MainTestClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.LocalClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The emote badge's cooldown ticker (ENG-55). It used to run for the rest of
 * the session after the first emote, because its gate was "a deadline has ever
 * been set" rather than "the deadline is still ahead". Nothing on screen showed
 * that; the only symptom was `SeatEmoteBadge` recomposing four times a second
 * forever. These tests observe the loop directly: the ticker has to read the
 * clock to run, so a clock that stops being read is a ticker that stopped.
 *
 * Time is virtual. [VirtualClock] reports the harness's own `mainClock`, so the
 * ticker's `delay`s and the deadline it compares against move together, and
 * `advanceTimeBy` is the only clock anyone touches. A suspended `delay` is not
 * pending work, so `waitForIdle` settles between steps instead of running the
 * cooldown out.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34])
class SeatEmoteBadgeTest {

    private class VirtualClock(private val mainClock: MainTestClock) : Clock {
        var reads = 0
            private set

        override fun now(): Instant {
            reads++
            return Instant.fromEpochMilliseconds(mainClock.currentTime)
        }
    }

    @Test
    fun ticker_countsDownThenStopsOnceTheCooldownPasses() = runComposeUiTest {
        val clock = VirtualClock(mainClock)
        val deadline = mainClock.currentTime + COOLDOWN_MS
        setContent {
            PreviewContent {
                CompositionLocalProvider(LocalClock provides clock) {
                    SeatEmoteBadge(emojis = EMOJIS, cooldownEndsAtEpochMs = deadline, onBlast = {})
                }
            }
        }
        onNodeWithText("8s").assertIsDisplayed()

        mainClock.advanceTimeBy(3_100)
        onNodeWithText("5s").assertIsDisplayed()

        mainClock.advanceTimeBy(COOLDOWN_MS)
        onNodeWithText(TRIGGER).assertIsDisplayed()
        val readsWhenOver = clock.reads

        mainClock.advanceTimeBy(60_000)
        waitForIdle()
        assertEquals(
            readsWhenOver,
            clock.reads,
            "the ticker went on polling the clock after the cooldown ended",
        )
    }

    @Test
    fun aFreshDeadline_rearmsTheTicker() = runComposeUiTest {
        val clock = VirtualClock(mainClock)
        var deadline by mutableStateOf(mainClock.currentTime + COOLDOWN_MS)
        setContent {
            PreviewContent {
                CompositionLocalProvider(LocalClock provides clock) {
                    SeatEmoteBadge(emojis = EMOJIS, cooldownEndsAtEpochMs = deadline, onBlast = {})
                }
            }
        }
        mainClock.advanceTimeBy(COOLDOWN_MS + 1_000)
        onNodeWithText(TRIGGER).assertIsDisplayed()

        runOnIdle { deadline = mainClock.currentTime + COOLDOWN_MS }
        onNodeWithText("8s").assertIsDisplayed()

        mainClock.advanceTimeBy(COOLDOWN_MS + 1_000)
        onNodeWithText(TRIGGER).assertIsDisplayed()
    }

    @Test
    fun noCooldown_neverStartsTheTicker() = runComposeUiTest {
        val clock = VirtualClock(mainClock)
        setContent {
            PreviewContent {
                CompositionLocalProvider(LocalClock provides clock) {
                    SeatEmoteBadge(emojis = EMOJIS, cooldownEndsAtEpochMs = 0L, onBlast = {})
                }
            }
        }
        onNodeWithText(TRIGGER).assertIsDisplayed()
        val readsAtMount = clock.reads

        mainClock.advanceTimeBy(60_000)
        waitForIdle()
        assertEquals(readsAtMount, clock.reads, "an idle badge should not be polling the clock")
    }

    private companion object {
        /** Matches `EMOJI_COOLDOWN_MS` in the ViewModel. */
        const val COOLDOWN_MS = 8_000L
        const val TRIGGER = "😀"
        val EMOJIS = listOf("🔥", "😂", "👀")
    }
}
