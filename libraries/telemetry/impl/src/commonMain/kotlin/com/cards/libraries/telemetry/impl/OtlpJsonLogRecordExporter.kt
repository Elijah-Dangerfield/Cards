package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.Catching
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.logging.SeverityNumber
import io.opentelemetry.kotlin.logging.export.LogRecordExporter
import io.opentelemetry.kotlin.logging.model.ReadableLogRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OTLP/HTTP JSON log exporter backed by our own Ktor client. Replaces the
 * library's `otlpHttpLogRecordExporter`, whose internal fire-and-forget
 * export coroutine has no exception handler — a DNS/connect failure while
 * offline escaped it and crashed the app (2026-07-11 emulator drill).
 * Owning the POST keeps every failure inside [Catching], reported to the
 * batch processor as a Failure result code.
 *
 * Wire format is the OTLP JSON mapping (proto3 JSON: int64 as string,
 * attribute values as typed one-of objects).
 */
internal class OtlpJsonLogRecordExporter(
    private val baseUrl: String,
    private val client: HttpClient,
) : LogRecordExporter {

    override suspend fun export(telemetry: List<ReadableLogRecord>): OperationResultCode {
        if (telemetry.isEmpty()) return OperationResultCode.Success
        return Catching {
            val response = client.post("$baseUrl/v1/logs") {
                contentType(ContentType.Application.Json)
                setBody(payload(telemetry).toString())
            }
            if (response.status.isSuccess()) OperationResultCode.Success else OperationResultCode.Failure
        }.getOrElse { OperationResultCode.Failure }
    }

    override suspend fun forceFlush(): OperationResultCode = OperationResultCode.Success

    override suspend fun shutdown(): OperationResultCode = OperationResultCode.Success

    internal fun payload(records: List<ReadableLogRecord>): JsonObject = buildJsonObject {
        putJsonArray("resourceLogs") {
            records.groupBy { it.resource.attributes }.forEach { (resourceAttributes, resourceRecords) ->
                addJsonObject {
                    putJsonObject("resource") { putAttributeList(resourceAttributes) }
                    putJsonArray("scopeLogs") {
                        resourceRecords.groupBy { it.instrumentationScopeInfo }.forEach { (scope, scopeRecords) ->
                            addJsonObject {
                                putJsonObject("scope") {
                                    put("name", scope.name)
                                    val version: String? = scope.version
                                    version?.let { put("version", it) }
                                }
                                putJsonArray("logRecords") {
                                    scopeRecords.forEach { add(it.toJson()) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun ReadableLogRecord.toJson(): JsonObject = buildJsonObject {
        val time: Long? = timestamp
        val observed: Long? = observedTimestamp
        (time ?: observed)?.let { put("timeUnixNano", it.toString()) }
        observed?.let { put("observedTimeUnixNano", it.toString()) }
        put("severityNumber", (severityNumber ?: SeverityNumber.UNKNOWN).ordinal)
        val text: String? = severityText
        text?.let { put("severityText", it) }
        val event: String? = eventName
        event?.let { put("eventName", it) }
        val bodyValue: Any? = body
        bodyValue?.let { putJsonObject("body") { put("stringValue", it.toString()) } }
        putAttributeList(attributes)
    }

    private fun JsonObjectBuilder.putAttributeList(attributes: Map<String, Any?>) {
        putJsonArray("attributes") {
            attributes.forEach { (key, value) ->
                if (value == null) return@forEach
                addJsonObject {
                    put("key", key)
                    putJsonObject("value") {
                        when (value) {
                            is Boolean -> put("boolValue", value)
                            is Int -> put("intValue", value.toString())
                            is Long -> put("intValue", value.toString())
                            is Float -> put("doubleValue", value.toDouble())
                            is Double -> put("doubleValue", value)
                            else -> put("stringValue", value.toString())
                        }
                    }
                }
            }
        }
    }
}
