package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.Catching
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.logging.export.LogRecordExporter
import io.opentelemetry.kotlin.logging.model.ReadableLogRecord

/**
 * The 0.5.0 batch processor runs exports in an unsupervised coroutine: an
 * exception out of [LogRecordExporter.export] is a process crash, not a
 * dropped batch. Reproduced 2026-07-11 — airplane mode made the OTLP
 * exporter's DNS lookup throw `UnknownHostException` and killed the app.
 * Telemetry must never crash the app, so every delegate call is contained
 * here and failure is reported through the result code the processor
 * already handles.
 */
internal class FailSafeLogRecordExporter(
    private val delegate: LogRecordExporter,
) : LogRecordExporter {

    override suspend fun export(telemetry: List<ReadableLogRecord>): OperationResultCode =
        Catching { delegate.export(telemetry) }.getOrElse { OperationResultCode.Failure }

    override suspend fun forceFlush(): OperationResultCode =
        Catching { delegate.forceFlush() }.getOrElse { OperationResultCode.Failure }

    override suspend fun shutdown(): OperationResultCode =
        Catching { delegate.shutdown() }.getOrElse { OperationResultCode.Failure }
}
