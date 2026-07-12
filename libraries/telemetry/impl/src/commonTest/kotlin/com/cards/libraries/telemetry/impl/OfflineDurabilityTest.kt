package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.logging.export.LogRecordExporter
import io.opentelemetry.kotlin.logging.model.ReadableLogRecord
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path

/**
 * ENG-25: the plain batch processor drops a failed export batch, so events
 * emitted offline were lost for good (2026-07-11 offline drill). The durable
 * chain persists every batch to disk before export and only deletes it once
 * the gateway acknowledges — a batch that can't ship in this process's
 * lifetime ships from a later launch that shares the buffer directory.
 *
 * Runs against the real SDK + persistence machinery on the real file system,
 * so waits are real-time (bounded by the short flush cadence passed in), not
 * virtual.
 */
class OfflineDurabilityTest {

    private class GateableExporter(private val online: Boolean) : LogRecordExporter {
        val exported = MutableStateFlow<List<ReadableLogRecord>>(emptyList())

        override suspend fun export(telemetry: List<ReadableLogRecord>): OperationResultCode {
            if (!online) return OperationResultCode.Failure
            exported.update { it + telemetry }
            return OperationResultCode.Success
        }

        override suspend fun forceFlush(): OperationResultCode = OperationResultCode.Success
        override suspend fun shutdown(): OperationResultCode = OperationResultCode.Success
    }

    private var offline = false

    @AfterTest
    fun tearDown() {
        KLog.clearTrees()
    }

    private fun plantTree(exporter: LogRecordExporter, bufferDirectory: Path, scheduleDelayMs: Long): GrafanaLogTree {
        val tree = GrafanaLogTree(
            exportEnabled = { true },
            sampleRate = { 1.0 },
            klogForwardingEnabled = { false },
            currentSessionId = { "session-uuid-1" },
            currentInstallId = { "install-uuid-1" },
            isOffline = { offline },
            processorFactory = {
                durableLogRecordProcessor(
                    exporter = exporter,
                    bufferDirectory = bufferDirectory,
                    scheduleDelayMs = scheduleDelayMs,
                )
            },
        )
        KLog.plant(tree)
        return tree
    }

    private suspend fun GateableExporter.awaitRecord(eventName: String): ReadableLogRecord =
        withContext(Dispatchers.Default) {
            withTimeout(15_000) {
                exported.first { records -> records.any { it.eventName == eventName } }
            }
        }.first { it.eventName == eventName }

    @Test
    fun eventsEmittedOffline_surviveToNextLaunch() = runTest(timeout = 30.seconds) {
        val bufferDirectory = testTelemetryBufferDirectory("otel-durability-${Random.nextLong()}")

        offline = true
        val offlineExporter = GateableExporter(online = false)
        val firstLaunch = plantTree(offlineExporter, bufferDirectory, scheduleDelayMs = 60_000)
        KLog.logEvent("hand.completed", "hand_number" to 4)
        // flush on a real-time dispatcher: the SDK's internal flush timeouts
        // schedule against the caller's clock, and the test dispatcher's
        // virtual clock would fire them before the real work completes.
        withContext(Dispatchers.Default) { firstLaunch.flushExports() }
        KLog.clearTrees()

        offline = false
        val onlineExporter = GateableExporter(online = true)
        plantTree(onlineExporter, bufferDirectory, scheduleDelayMs = 50)
        KLog.logEvent("app.launched")

        val survivor = onlineExporter.awaitRecord("hand.completed")
        assertEquals(4L, survivor.attributes["hand_number"])
        assertEquals("session-uuid-1", survivor.attributes["session_id"])
        assertEquals(true, survivor.attributes["is_offline"], "is_offline captures emit time, not ship time")
    }

    @Test
    fun backgroundFlush_shipsTheRamBatchWithoutWaitingForTheTick() = runTest(timeout = 30.seconds) {
        val bufferDirectory = testTelemetryBufferDirectory("otel-flush-${Random.nextLong()}")
        val exporter = GateableExporter(online = true)
        val tree = plantTree(exporter, bufferDirectory, scheduleDelayMs = 60_000)

        KLog.logEvent("app.backgrounded", "session_duration_sec" to 42L)
        withContext(Dispatchers.Default) { tree.flushExports() }

        val records = exporter.exported.value
        assertTrue(
            records.any { it.eventName == "app.backgrounded" },
            "flush must drain the RAM batch through the buffer and export it immediately",
        )
    }
}
