package com.dangerfield.cards.libraries.telemetry.impl

import io.opentelemetry.kotlin.context.Context
import io.opentelemetry.kotlin.export.BatchTelemetryDefaults
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.init.LogExportConfigDsl
import io.opentelemetry.kotlin.logging.export.LogRecordExporter
import io.opentelemetry.kotlin.logging.export.LogRecordProcessor
import io.opentelemetry.kotlin.logging.export.persistingLogRecordProcessor
import io.opentelemetry.kotlin.logging.model.ReadWriteLogRecord
import okio.Path

/**
 * The export chain behind [GrafanaLogTree]: batch → disk → OTLP. Batches are
 * written to [bufferDirectory] before export and deleted only after the
 * gateway acknowledges them, so events emitted offline survive process death
 * and ship on a later launch (the plain batch processor drops a failed batch
 * on the floor — verified in the 2026-07-11 offline drill). Leftover batches
 * from previous launches are picked up by the same [scheduleDelayMs] flush
 * loop once the SDK constructs.
 *
 * The exporter is wrapped in [FailSafeLogRecordExporter] here so no caller
 * can compose the chain without the crash containment.
 */
internal fun LogExportConfigDsl.durableLogRecordProcessor(
    exporter: LogRecordExporter,
    bufferDirectory: Path,
    scheduleDelayMs: Long = BatchTelemetryDefaults.SCHEDULE_DELAY_MS,
): LogRecordProcessor = persistingLogRecordProcessor(
    processor = NoMutationProcessor,
    exporter = FailSafeLogRecordExporter(exporter),
    cacheDirectory = bufferDirectory,
    scheduleDelayMs = scheduleDelayMs,
)

/**
 * `persistingLogRecordProcessor` requires a record-mutation stage; we don't
 * mutate records, so this stage does nothing.
 */
private object NoMutationProcessor : LogRecordProcessor {
    override fun onEmit(log: ReadWriteLogRecord, context: Context) = Unit
    override suspend fun forceFlush(): OperationResultCode = OperationResultCode.Success
    override suspend fun shutdown(): OperationResultCode = OperationResultCode.Success
}
