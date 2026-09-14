package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.logging.KLog
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun previousExitDetail_landsAsAttributesOnItsOwnEvent() {
        plantTree()

        logPreviousExitDetail(
            PreviousExitDetail(importance = 100, pssKb = 234_567L, rssKb = 345_678L, description = "lmkd"),
        )

        val record = processor.records.single()
        assertEquals("app.exit_detail", record.eventName)
        assertEquals(100, record.attributes["previous_exit_importance"])
        assertEquals(234_567L, record.attributes["previous_exit_pss_kb"])
        assertEquals(345_678L, record.attributes["previous_exit_rss_kb"])
        assertEquals("lmkd", record.attributes["previous_exit_description"])
    }

    @Test
    fun previousExitDetail_nullFields_areDroppedNotSentAsNull() {
        plantTree()

        logPreviousExitDetail(PreviousExitDetail(importance = null, pssKb = null, rssKb = null, description = null))

        val record = processor.records.single()
        assertEquals("app.exit_detail", record.eventName)
        assertTrue("previous_exit_importance" !in record.attributes)
        assertTrue("previous_exit_pss_kb" !in record.attributes)
        assertTrue("previous_exit_rss_kb" !in record.attributes)
        assertTrue("previous_exit_description" !in record.attributes)
    }
}
