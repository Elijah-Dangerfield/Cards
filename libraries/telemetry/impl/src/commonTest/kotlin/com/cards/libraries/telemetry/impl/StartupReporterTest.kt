package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.logging.KLog
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reporter's whole job is deciding *whether* a measurement is worth
 * reporting, so that decision is what these cover. The platform clocks
 * themselves are not exercised here — they are unfakeable off-device, and a
 * fake would only assert that subtraction works.
 */
class StartupReporterTest {

    private val processor = RecordingLogRecordProcessor()

    @BeforeTest
    fun plantTree() {
        KLog.plant(
            GrafanaLogTree(
                exportEnabled = { true },
                sampleRate = { 1.0 },
                klogForwardingEnabled = { false },
                currentSessionId = { "session-uuid-1" },
                currentInstallId = { "install-uuid-1" },
                isOffline = { false },
                installFacts = { RetailInstallFacts },
                processorFactory = { processor },
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        KLog.clearTrees()
    }

    @Test
    fun reportsStartupMillisOnceTheAppIsReady() {
        reporterReporting(1_234L).onAppReady()

        val record = processor.records.single()
        assertEquals("app.startup", record.eventName)
        assertEquals(1_234L, record.attributes["startup_ms"])
    }

    /**
     * An Activity is recreated on every rotation and theme change, and each
     * recreation draws a fresh first frame. Only the first is a startup;
     * counting the rest would report a rotation as a sub-millisecond launch and
     * drag the median toward zero.
     */
    @Test
    fun reportsOnlyOncePerProcess() {
        val reporter = reporterReporting(900L)

        reporter.onAppReady()
        reporter.onAppReady()
        reporter.onAppReady()

        assertEquals(1, processor.records.size)
    }

    /**
     * The background-start case: the system started the process hours before
     * anyone opened the app. A real elapsed time, but not a launch, and left in
     * it would move every percentile on the dashboard.
     */
    @Test
    fun dropsImplausiblyLongStartups() {
        reporterReporting(elapsedMs = 45 * 60 * 1_000L).onAppReady()

        assertTrue(processor.records.isEmpty())
    }

    /** A wall-clock correction mid-launch. Nonsense rather than a fast boot. */
    @Test
    fun dropsNegativeElapsedTime() {
        reporterReporting(elapsedMs = -50L).onAppReady()

        assertTrue(processor.records.isEmpty())
    }

    /** A genuinely slow launch on a bad device is the signal, not noise. */
    @Test
    fun keepsGenuinelySlowStartups() {
        reporterReporting(elapsedMs = 12_000L).onAppReady()

        assertEquals(12_000L, processor.records.single().attributes["startup_ms"])
    }

    /** Platforms with no process clock report nothing rather than a zero. */
    @Test
    fun reportsNothingWhenThePlatformCannotMeasure() {
        StartupReporter(NoOpProcessStartTimeProvider()).onAppReady()

        assertTrue(processor.records.isEmpty())
    }

    private fun reporterReporting(elapsedMs: Long) = StartupReporter(
        object : ProcessStartTimeProvider {
            override fun elapsedSinceProcessStartMs() = elapsedMs
        },
    )
}
