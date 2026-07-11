package com.dangerfield.cards.libraries.telemetry.impl

import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.logging.export.LogRecordExporter
import io.opentelemetry.kotlin.logging.model.ReadableLogRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FailSafeLogRecordExporterTest {

    private class ThrowingExporter(private val throwable: Throwable) : LogRecordExporter {
        override suspend fun export(telemetry: List<ReadableLogRecord>): OperationResultCode =
            throw throwable

        override suspend fun forceFlush(): OperationResultCode = throw throwable

        override suspend fun shutdown(): OperationResultCode = throw throwable
    }

    @Test
    fun exporterException_becomesFailureInsteadOfEscaping() = runTest {
        // The airplane-mode repro: the OTLP exporter throws UnknownHostException
        // from inside export and the batch processor's coroutine has no handler.
        val exporter = FailSafeLogRecordExporter(
            ThrowingExporter(RuntimeException("Unable to resolve host otlp-gateway")),
        )

        assertEquals(OperationResultCode.Failure, exporter.export(emptyList()))
        assertEquals(OperationResultCode.Failure, exporter.forceFlush())
        assertEquals(OperationResultCode.Failure, exporter.shutdown())
    }

    @Test
    fun successfulExport_passesThrough() = runTest {
        val exporter = FailSafeLogRecordExporter(
            object : LogRecordExporter {
                override suspend fun export(telemetry: List<ReadableLogRecord>): OperationResultCode =
                    OperationResultCode.Success

                override suspend fun forceFlush(): OperationResultCode = OperationResultCode.Success

                override suspend fun shutdown(): OperationResultCode = OperationResultCode.Success
            },
        )

        assertEquals(OperationResultCode.Success, exporter.export(emptyList()))
    }

    @Test
    fun cancellation_stillPropagates() = runTest {
        val exporter = FailSafeLogRecordExporter(ThrowingExporter(CancellationException("scope died")))

        assertFailsWith<CancellationException> { exporter.export(emptyList()) }
    }
}
