package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.logging.KLog
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Plumbing check for `app.launched`: whatever [PreviousExit] the platform
 * provider reports must land verbatim as the `previous_exit` attribute.
 */
class AppLaunchedEventTest {

    private val processor = RecordingLogRecordProcessor()

    private fun plantTree() {
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
    fun previousExitValue_landsAsAttribute() {
        plantTree()

        logAppLaunched(PreviousExit.Crash)

        val record = processor.records.single()
        assertEquals("app.launched", record.eventName)
        assertEquals(true, record.attributes["cold_start"])
        assertEquals("crash", record.attributes["previous_exit"])
    }

    @Test
    fun unknownExit_isReportedHonestlyNotOmitted() {
        plantTree()

        logAppLaunched(PreviousExit.Unknown)

        assertEquals("unknown", processor.records.single().attributes["previous_exit"])
    }
}
