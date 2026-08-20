package com.dangerfield.cards.libraries.telemetry.impl

import io.opentelemetry.kotlin.context.Context
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.logging.SeverityNumber
import io.opentelemetry.kotlin.logging.export.LogRecordProcessor
import io.opentelemetry.kotlin.logging.model.ReadWriteLogRecord

/**
 * Synchronous stand-in for the batch processor: captures a snapshot of each
 * emitted record so assertions never race an export coroutine.
 */
internal class RecordingLogRecordProcessor : LogRecordProcessor {

    class Recorded(
        val eventName: String?,
        val body: Any?,
        val severityNumber: SeverityNumber?,
        val attributes: Map<String, Any>,
        val resourceAttributes: Map<String, Any>,
    )

    val records = mutableListOf<Recorded>()

    override fun onEmit(log: ReadWriteLogRecord, context: Context) {
        records += Recorded(
            eventName = log.eventName,
            body = log.body,
            severityNumber = log.severityNumber,
            attributes = log.attributes.toMap(),
            resourceAttributes = log.resource.attributes.toMap(),
        )
    }

    override suspend fun forceFlush(): OperationResultCode = OperationResultCode.Success

    override suspend fun shutdown(): OperationResultCode = OperationResultCode.Success
}
