package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import io.opentelemetry.kotlin.logging.SeverityNumber
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrafanaLogTreeTest {

    private val processor = RecordingLogRecordProcessor()

    private var enabled = true
    private var sampleRate = 1.0
    private var klogForwardingEnabled = false
    private var sessionId: String? = "session-uuid-1"
    private var installId: String? = "install-uuid-1"

    private fun plantTree() {
        KLog.plant(
            GrafanaLogTree(
                exportEnabled = { enabled },
                sampleRate = { sampleRate },
                klogForwardingEnabled = { klogForwardingEnabled },
                currentSessionId = { sessionId },
                currentInstallId = { installId },
                processorFactory = { processor },
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        KLog.clearTrees()
    }

    @Test
    fun logEvent_forwardsEventNameWithCorrelationAttributes() {
        plantTree()

        KLog.logEvent("matchmaking.abandoned", "phase" to "wait", "wait_ms" to 1200L)

        assertEquals(1, processor.records.size)
        val record = processor.records.single()
        assertEquals("matchmaking.abandoned", record.eventName)
        assertEquals(SeverityNumber.INFO, record.severityNumber)
        assertEquals("session-uuid-1", record.attributes["session_id"])
        assertEquals("install-uuid-1", record.attributes["install_id"])
        assertEquals("wait", record.attributes["phase"])
        assertEquals(1200L, record.attributes["wait_ms"])
    }

    @Test
    fun klogForwardingOff_forwardsOnlyEvents() {
        plantTree()

        KLog.i("just an ordinary info line")
        KLog.w("a warn line, not forwarded with the flag off")
        KLog.e("an error line, still not forwarded")
        KLog.logEvent("hand.completed", "won" to true)

        val record = processor.records.single()
        assertEquals("hand.completed", record.eventName)
    }

    @Test
    fun klogForwardingOn_forwardsWarnAndAbove_asPlainLogs() {
        klogForwardingEnabled = true
        plantTree()

        KLog.withTag("RoomSocket").w("reconnect attempt 3 backing off")

        val record = processor.records.single()
        assertEquals(null, record.eventName)
        assertEquals("reconnect attempt 3 backing off", record.body)
        assertEquals(SeverityNumber.WARN, record.severityNumber)
        assertEquals("RoomSocket", record.attributes["tag"])
        assertEquals("session-uuid-1", record.attributes["session_id"])
        assertEquals("install-uuid-1", record.attributes["install_id"])
    }

    @Test
    fun klogForwardingOn_errorWithThrowable_carriesExceptionAttributes() {
        klogForwardingEnabled = true
        plantTree()

        KLog.e("send after close", IllegalStateException("socket already closed"))

        val record = processor.records.single()
        assertEquals(null, record.eventName)
        assertEquals("send after close", record.body)
        assertEquals(SeverityNumber.ERROR, record.severityNumber)
        assertEquals("IllegalStateException", record.attributes["exception_type"])
        assertEquals("socket already closed", record.attributes["exception_message"])
    }

    @Test
    fun klogForwardingOn_infoNonEvent_isStillNotForwarded() {
        klogForwardingEnabled = true
        plantTree()

        KLog.i("chatty info line")
        KLog.d("debug line")

        assertTrue(processor.records.isEmpty())
    }

    @Test
    fun klogForwardingOn_eventsKeepTheirEventName() {
        klogForwardingEnabled = true
        plantTree()

        KLog.logEvent("room.joined")

        assertEquals("room.joined", processor.records.single().eventName)
    }

    @Test
    fun klogForwardingOn_killSwitchStillDropsEverything() {
        enabled = false
        klogForwardingEnabled = true
        plantTree()

        KLog.w("warn line")
        KLog.logEvent("app.launched")

        assertTrue(processor.records.isEmpty())
    }

    @Test
    fun klogForwardingOn_sampledOutSession_dropsWarnLogsToo() {
        sampleRate = 0.0
        klogForwardingEnabled = true
        plantTree()

        KLog.w("warn line")

        assertTrue(processor.records.isEmpty())
    }

    @Test
    fun killSwitchOff_dropsExport() {
        enabled = false
        plantTree()

        KLog.logEvent("app.launched")

        assertTrue(processor.records.isEmpty())
    }

    @Test
    fun sampleRateZero_dropsExport() {
        sampleRate = 0.0
        plantTree()

        KLog.logEvent("app.launched")

        assertTrue(processor.records.isEmpty())
    }

    @Test
    fun samplingIsStablePerSession() {
        sampleRate = 0.5
        plantTree()

        KLog.logEvent("hand.completed", "hand_number" to 1)
        KLog.logEvent("hand.completed", "hand_number" to 2)

        val counts = processor.records.size
        assertTrue(counts == 0 || counts == 2, "a session's events are all-or-nothing, got $counts of 2")
    }

    @Test
    fun sessionRollover_stampsNewSessionIdOnNextEvent() {
        plantTree()

        KLog.logEvent("app.launched")
        sessionId = "session-uuid-2"
        KLog.logEvent("app.foregrounded")

        assertEquals("session-uuid-1", processor.records[0].attributes["session_id"])
        assertEquals("session-uuid-2", processor.records[1].attributes["session_id"])
    }

    @Test
    fun nullInstallId_omitsAttributeInsteadOfCrashing() {
        installId = null
        plantTree()

        KLog.logEvent("app.launched")

        val record = processor.records.single()
        assertEquals(null, record.attributes["install_id"])
        assertEquals("session-uuid-1", record.attributes["session_id"])
    }

    @Test
    fun nonScalarAttribute_isStringified_andNullsAreDropped() {
        plantTree()

        KLog.logEvent(
            "purchase.failed",
            "error" to CustomError("timeout"),
            "product_id" to null,
            "attempt" to 3,
            "final" to true,
        )

        val record = processor.records.single()
        assertEquals("CustomError(reason=timeout)", record.attributes["error"])
        assertEquals(null, record.attributes["product_id"])
        assertEquals(3L, record.attributes["attempt"])
        assertEquals(true, record.attributes["final"])
    }

    private data class CustomError(val reason: String)
}
