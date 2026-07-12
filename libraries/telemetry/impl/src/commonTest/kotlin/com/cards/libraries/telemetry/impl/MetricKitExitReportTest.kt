package com.dangerfield.cards.libraries.telemetry.impl

import kotlin.test.Test
import kotlin.test.assertEquals

class MetricKitExitReportTest {

    private var stored: String? = null
    private val report = LatestExitReport(read = { stored }, write = { stored = it })

    @Test
    fun classification_picksTheMostSevereExitInTheWindow() {
        assertEquals(
            PreviousExit.Crash,
            ForegroundExitCounts(normal = 12, watchdog = 1, memoryLimit = 2, badAccess = 1).classify(),
        )
        assertEquals(
            PreviousExit.Anr,
            ForegroundExitCounts(normal = 12, watchdog = 1, memoryLimit = 2).classify(),
        )
        assertEquals(
            PreviousExit.Oom,
            ForegroundExitCounts(normal = 12, memoryLimit = 2).classify(),
        )
        assertEquals(PreviousExit.Clean, ForegroundExitCounts(normal = 12).classify())
    }

    @Test
    fun abnormalAndIllegalInstructionExits_countAsCrashes() {
        assertEquals(PreviousExit.Crash, ForegroundExitCounts(abnormal = 1).classify())
        assertEquals(PreviousExit.Crash, ForegroundExitCounts(illegalInstruction = 1).classify())
    }

    @Test
    fun report_isConsumedByExactlyOneLaunch() {
        report.record(ForegroundExitCounts(badAccess = 1))

        assertEquals(PreviousExit.Crash, report.consume())
        assertEquals(PreviousExit.Unknown, report.consume(), "a second launch must not re-report the same window")
    }

    @Test
    fun emptyWindow_doesNotOverwriteAnUnconsumedReport() {
        report.record(ForegroundExitCounts(watchdog = 1))
        report.record(ForegroundExitCounts())

        assertEquals(PreviousExit.Anr, report.consume())
    }

    @Test
    fun freshInstall_reportsUnknown() {
        assertEquals(PreviousExit.Unknown, report.consume())
    }

    @Test
    fun corruptStoredValue_fallsBackToUnknown() {
        stored = "not-a-previous-exit"

        assertEquals(PreviousExit.Unknown, report.consume())
    }
}
