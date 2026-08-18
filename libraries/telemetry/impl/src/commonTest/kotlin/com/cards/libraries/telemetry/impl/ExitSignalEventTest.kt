package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.logging.KLog
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Plumbing checks for the two ENG-42 signals: whatever the tracker and the
 * MetricKit subscriber work out has to survive the trip onto the record as
 * queryable attributes, or the dashboard has nothing to chart.
 */
class ExitSignalEventTest {

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
                processorFactory = { processor },
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        KLog.clearTrees()
    }

    @Test
    fun previousRun_carriesOutcomeAndTheJoinKey() {
        logPreviousRun(
            PreviousRun(
                outcome = PreviousRunOutcome.ForegroundTermination,
                sessionId = "session-a",
                ageSeconds = 86,
            ),
        )

        val record = processor.records.single()
        assertEquals("app.previous_run", record.eventName)
        assertEquals("foreground_termination", record.attributes["outcome"])
        assertEquals("session-a", record.attributes["previous_session_id"])
        assertEquals(86L, record.attributes["previous_run_age_sec"])
    }

    @Test
    fun previousRunOnAFreshInstall_stillReportsAnOutcome() {
        logPreviousRun(PreviousRun(PreviousRunOutcome.Unknown, sessionId = null, ageSeconds = null))

        val record = processor.records.single()
        assertEquals("unknown", record.attributes["outcome"])
        assertFalse(record.attributes.containsKey("previous_session_id"))
    }

    @Test
    fun exitMetrics_keepEveryCountNotJustTheSevereOne() {
        logForegroundExitMetrics(
            ForegroundExitCounts(normal = 12, watchdog = 3, memoryLimit = 1),
        )

        val record = processor.records.single()
        assertEquals("app.exit_metrics", record.eventName)
        assertEquals(12L, record.attributes["exit_normal"])
        assertEquals(3L, record.attributes["exit_watchdog"])
        assertEquals(1L, record.attributes["exit_memory_limit"])
        assertEquals("anr", record.attributes["classified_as"])
    }
}
