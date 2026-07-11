package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.logging.SeverityNumber
import io.opentelemetry.kotlin.logging.export.LogRecordProcessor
import io.opentelemetry.kotlin.logging.model.ReadWriteLogRecord
import io.opentelemetry.kotlin.context.Context
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrafanaLogTreeTest {

    private val processor = RecordingLogRecordProcessor()

    private var enabled = true
    private var sampleRate = 1.0
    private var sessionId: String? = "session-uuid-1"
    private var installId: String? = "install-uuid-1"

    private fun plantTree() {
        KLog.plant(
            GrafanaLogTree(
                exportEnabled = { enabled },
                sampleRate = { sampleRate },
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
    fun plainLog_isNotForwarded() {
        plantTree()

        KLog.i("just an ordinary info line")
        KLog.e("an error line, still not an event")

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

/**
 * Synchronous stand-in for the batch processor: captures a snapshot of each
 * emitted record so assertions never race an export coroutine.
 */
private class RecordingLogRecordProcessor : LogRecordProcessor {

    class Recorded(
        val eventName: String?,
        val body: Any?,
        val severityNumber: SeverityNumber?,
        val attributes: Map<String, Any>,
    )

    val records = mutableListOf<Recorded>()

    override fun onEmit(log: ReadWriteLogRecord, context: Context) {
        records += Recorded(
            eventName = log.eventName,
            body = log.body,
            severityNumber = log.severityNumber,
            attributes = log.attributes.toMap(),
        )
    }

    override suspend fun forceFlush(): OperationResultCode = OperationResultCode.Success

    override suspend fun shutdown(): OperationResultCode = OperationResultCode.Success
}
