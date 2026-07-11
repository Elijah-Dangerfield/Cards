package com.dangerfield.cards.libraries.telemetry.impl

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.opentelemetry.kotlin.InstrumentationScopeInfo
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.logging.SeverityNumber
import io.opentelemetry.kotlin.logging.model.ReadableLogRecord
import io.opentelemetry.kotlin.resource.MutableResource
import io.opentelemetry.kotlin.resource.Resource
import io.opentelemetry.kotlin.tracing.SpanContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtlpJsonLogRecordExporterTest {

    private class FakeResource(override val attributes: Map<String, Any>) : Resource {
        override val schemaUrl: String? = null
        override fun asNewResource(action: (MutableResource) -> Unit): Resource = this
        override fun merge(resource: Resource): Resource = this
    }

    private class FakeScope(override val name: String, override val version: String?) :
        InstrumentationScopeInfo {
        override val schemaUrl: String? = null
        override val attributes: Map<String, Any> = emptyMap()
    }

    private class FakeRecord(
        override val eventName: String?,
        override val attributes: Map<String, Any>,
        override val resource: Resource = FakeResource(mapOf("service.name" to "cards-client")),
    ) : ReadableLogRecord {
        override val timestamp: Long? = 1_783_793_000_000_000_000
        override val observedTimestamp: Long? = 1_783_793_000_000_000_001
        override val severityNumber: SeverityNumber = SeverityNumber.INFO
        override val severityText: String? = "INFO"
        override val body: Any? = eventName
        override val spanContext: SpanContext get() = error("not used by the serializer")
        override val instrumentationScopeInfo: InstrumentationScopeInfo =
            FakeScope("cards-client", "0.1.0")
    }

    private fun exporter(engine: MockEngine) =
        OtlpJsonLogRecordExporter("https://otlp.example/otlp", HttpClient(engine))

    @Test
    fun payload_carriesEventNameTypedAttributesAndResource() {
        val record = FakeRecord(
            eventName = "hand.completed",
            attributes = mapOf(
                "session_id" to "s-1",
                "hand_number" to 4L,
                "won" to true,
                "ratio" to 0.5,
                "count" to 2,
            ),
        )

        val payload = exporter(MockEngine { respond("") }).payload(listOf(record))

        val resourceLog = payload["resourceLogs"]!!.jsonArray.single().jsonObject
        val resourceAttrs = resourceLog["resource"]!!.jsonObject["attributes"]!!.jsonArray
        assertEquals("service.name", resourceAttrs.single().jsonObject["key"]!!.jsonPrimitive.content)

        val scopeLog = resourceLog["scopeLogs"]!!.jsonArray.single().jsonObject
        assertEquals(
            "cards-client",
            scopeLog["scope"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )

        val logRecord = scopeLog["logRecords"]!!.jsonArray.single().jsonObject
        assertEquals("hand.completed", logRecord["eventName"]!!.jsonPrimitive.content)
        // INFO must serialize as OTLP severity 9 — guards the ordinal mapping.
        assertEquals("9", logRecord["severityNumber"]!!.jsonPrimitive.content)
        assertEquals("1783793000000000000", logRecord["timeUnixNano"]!!.jsonPrimitive.content)

        val attrs = logRecord["attributes"]!!.jsonArray.associate {
            it.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonObject
        }
        assertEquals("s-1", attrs["session_id"]!!["stringValue"]!!.jsonPrimitive.content)
        assertEquals("4", attrs["hand_number"]!!["intValue"]!!.jsonPrimitive.content)
        assertEquals("2", attrs["count"]!!["intValue"]!!.jsonPrimitive.content)
        assertEquals("true", attrs["won"]!!["boolValue"]!!.jsonPrimitive.content)
        assertEquals("0.5", attrs["ratio"]!!["doubleValue"]!!.jsonPrimitive.content)
    }

    @Test
    fun successfulPost_returnsSuccessAndSendsJsonBody() = runTest {
        var sentBody: String? = null
        val engine = MockEngine { request ->
            sentBody = (request.body as TextContent).text
            respond("", HttpStatusCode.OK)
        }

        val result = exporter(engine).export(listOf(FakeRecord("app.launched", emptyMap())))

        assertEquals(OperationResultCode.Success, result)
        val body = Json.parseToJsonElement(sentBody!!).jsonObject
        assertTrue("resourceLogs" in body)
    }

    @Test
    fun transportException_returnsFailureInsteadOfThrowing() = runTest {
        // The airplane-mode scenario: the engine throws instead of responding.
        val engine = MockEngine { error("Unable to resolve host otlp.example") }

        val result = exporter(engine).export(listOf(FakeRecord("app.launched", emptyMap())))

        assertEquals(OperationResultCode.Failure, result)
    }

    @Test
    fun serverError_returnsFailure() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }

        val result = exporter(engine).export(listOf(FakeRecord("app.launched", emptyMap())))

        assertEquals(OperationResultCode.Failure, result)
    }

    @Test
    fun emptyBatch_skipsTheNetworkEntirely() = runTest {
        val engine = MockEngine { error("must not be called") }

        assertEquals(OperationResultCode.Success, exporter(engine).export(emptyList()))
    }
}
