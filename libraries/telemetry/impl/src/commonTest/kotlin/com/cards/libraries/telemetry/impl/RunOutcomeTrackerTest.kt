package com.dangerfield.cards.libraries.telemetry.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The claim under test is the one ENG-42 turns on: swiping an app out of the
 * iOS app switcher backgrounds it before the process dies, so a force-quit
 * must read differently from a run the OS killed while it was still on screen.
 */
class RunOutcomeTrackerTest {

    private var stored: String? = null
    private var currentTime = Instant.fromEpochSeconds(1_000_000)

    private val store = object : RunMarkerStore {
        override fun read(): String? = stored
        override fun write(value: String?) {
            stored = value
        }
    }

    private val clock = object : Clock {
        override fun now(): Instant = currentTime
    }

    private val tracker = RunOutcomeTracker(store, clock)

    @Test
    fun runKilledWhileForegrounded_readsAsForegroundTermination() {
        tracker.mark("session-a", RunState.Foreground)

        assertEquals(PreviousRunOutcome.ForegroundTermination, tracker.consumePreviousRun().outcome)
    }

    @Test
    fun forceQuitFromTheAppSwitcher_readsAsBackgroundExit() {
        tracker.mark("session-a", RunState.Foreground)
        tracker.mark("session-a", RunState.Background)

        assertEquals(PreviousRunOutcome.BackgroundExit, tracker.consumePreviousRun().outcome)
    }

    @Test
    fun previousSessionId_survivesAsTheJoinKey() {
        tracker.mark("session-a", RunState.Foreground)

        assertEquals("session-a", tracker.consumePreviousRun().sessionId)
    }

    @Test
    fun ageReportsHowLongTheDeadRunWentUnheardFrom() {
        tracker.mark("session-a", RunState.Foreground)
        currentTime += 86.seconds

        assertEquals(86, tracker.consumePreviousRun().ageSeconds)
    }

    @Test
    fun wallClockMovingBackwards_reportsNoAgeRatherThanANegativeOne() {
        tracker.mark("session-a", RunState.Foreground)
        currentTime -= 1.hours

        assertNull(tracker.consumePreviousRun().ageSeconds)
    }

    @Test
    fun firstLaunchEver_reportsUnknown() {
        val previousRun = tracker.consumePreviousRun()

        assertEquals(PreviousRunOutcome.Unknown, previousRun.outcome)
        assertNull(previousRun.sessionId)
        assertNull(previousRun.ageSeconds)
    }

    @Test
    fun corruptMarker_degradesToUnknownInsteadOfThrowing() {
        stored = "{not json"

        assertEquals(PreviousRunOutcome.Unknown, tracker.consumePreviousRun().outcome)
    }

    @Test
    fun markerFromAnUnknownSchema_degradesToUnknown() {
        stored = """{"sessionId":"session-a","state":"Hibernating","updatedAtEpochSeconds":1}"""

        assertEquals(PreviousRunOutcome.Unknown, tracker.consumePreviousRun().outcome)
    }

    @Test
    fun latestTransitionWins_soAWarmReturnUndoesTheBackgroundReading() {
        tracker.mark("session-a", RunState.Foreground)
        tracker.mark("session-a", RunState.Background)
        tracker.mark("session-a", RunState.Foreground)

        assertEquals(PreviousRunOutcome.ForegroundTermination, tracker.consumePreviousRun().outcome)
    }
}
