package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.LogLevel
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

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
@OptIn(ExperimentalAtomicApi::class)
internal class OtlpJsonLogRecordExporter(
    private val baseUrl: String,
    private val client: HttpClient,
) : LogRecordExporter {

    private val logger = KLog.withTag(GrafanaLogTree.EXPORT_TAG)

    private val permanentRejections = AtomicInt(0)

    override suspend fun export(telemetry: List<ReadableLogRecord>): OperationResultCode {
        if (telemetry.isEmpty()) return OperationResultCode.Success
        return Catching {
            val response = client.post("$baseUrl/v1/logs") {
                contentType(ContentType.Application.Json)
                setBody(payload(telemetry).toString())
            }
            when {
                response.status.isSuccess() -> OperationResultCode.Success
                response.status.isPermanentRejection() -> {
                    reportPermanentRejection(response, telemetry.size)
                    // Success means "this batch is settled, delete it". A lie
                    // about the gateway, but the truthful answer keeps a batch
                    // Grafana will never accept at the head of the disk buffer
                    // forever, and takes every later session down with it.
                    OperationResultCode.Success
                }
                else -> {
                    logger.w {
                        "Grafana log export failed with ${response.status.value}; " +
                            "${telemetry.size} record(s) stay buffered for the next attempt."
                    }
                    OperationResultCode.Failure
                }
            }
        }.getOrElse { OperationResultCode.Failure }
    }

    /**
     * The response body is the only thing that says *what* Grafana objected
     * to — which field, which timestamp — so it rides on the line, truncated,
     * as structured data rather than in the message, where a near-unique
     * string would split one condition into an issue per occurrence.
     *
     * Read inside its own [Catching]: a body read that fails must not turn a
     * batch we have already decided to drop back into a Failure the disk
     * buffer replays forever (the ENG-80 deadlock this branch exists to break).
     *
     * The first refusal of the process reports at Error, the rest at Warn.
     * Every cause of a permanent rejection — revoked token, skewed clock,
     * a record shape the gateway won't take — refuses batch after batch for
     * as long as the process lives, and that is one condition worth one alert,
     * not an alert per batch. The Warn lines still breadcrumb, and carry the
     * running count, so the scale of the drop stays readable.
     */
    private suspend fun reportPermanentRejection(response: HttpResponse, records: Int) {
        val reason = Catching { response.bodyAsText() }
            .getOrNull()
            ?.ifBlank { "<empty>" }
            ?.truncated(MAX_RESPONSE_BODY_CHARS)
            ?: "<unreadable>"
        val rejections = permanentRejections.incrementAndFetch()
        val level = if (rejections == 1) LogLevel.Error else LogLevel.Warn
        logger.log(level) { scope ->
            scope.extra("records", records)
            scope.extra("rejected_batches", rejections)
            scope.extra("response_body", reason)
            "Grafana permanently refused a log batch with ${response.status.value}; " +
                "dropping it. Nothing behind it would ship until it did."
        }
    }

    /**
     * Retrying will not change the answer: a malformed payload, a revoked
     * token, or timestamps outside Loki's accepted window. The two exceptions
     * are the status codes that explicitly mean "ask again later".
     */
    private fun HttpStatusCode.isPermanentRejection(): Boolean =
        value in 400..499 &&
            this != HttpStatusCode.RequestTimeout &&
            this != HttpStatusCode.TooManyRequests

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
                            else -> put(
                                "stringValue",
                                value.toString().truncated(MAX_ATTRIBUTE_VALUE_CHARS),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun String.truncated(max: Int): String =
        if (length <= max) this else "${take(max)}...[+${length - max} chars]"

    private companion object {
        /**
         * Enough of Loki's refusal to name the field or the timestamp it
         * objected to, short enough to put on a Sentry event.
         */
        const val MAX_RESPONSE_BODY_CHARS = 512

        /**
         * Nothing derived from user content or a platform error string reaches
         * the gateway unbounded — a single multi-kilobyte value can cost the
         * whole batch, and the records in it are the ones describing whatever
         * went wrong. 512 keeps the front of an iOS NSError dump, where the
         * domain, code and description are; the marker reports what the full
         * length was, which is itself the answer to "was this too big?".
         */
        const val MAX_ATTRIBUTE_VALUE_CHARS = 512
    }
}
