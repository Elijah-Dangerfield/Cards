package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.LogEntry
import com.dangerfield.cards.libraries.core.logging.LogId
import com.dangerfield.cards.libraries.core.logging.LogLevel
import com.dangerfield.cards.libraries.core.logging.LogTree
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
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

    private val logs = CapturingLogTree()

    @BeforeTest
    fun plantLogTree() {
        KLog.plant(logs)
    }

    @AfterTest
    fun clearLogTrees() {
        KLog.clearTrees()
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

    @Test
    fun permanentRejection_settlesTheBatchSoItStopsBlockingTheOnesBehindIt() = runTest {
        // ENG-80. Failure means "keep it and try again", and the disk buffer is
        // ordered — so a batch Grafana will never accept (malformed payload,
        // revoked token, timestamps outside Loki's window) sits at the head
        // forever and nothing after it ever ships. Dropping one batch costs
        // less than losing every session that follows.
        listOf(
            HttpStatusCode.BadRequest,
            HttpStatusCode.Unauthorized,
            HttpStatusCode.PayloadTooLarge,
        ).forEach { status ->
            val engine = MockEngine { respondError(status) }

            val result = exporter(engine).export(listOf(FakeRecord("app.launched", emptyMap())))

            assertEquals(OperationResultCode.Success, result, "$status should settle the batch")
        }
    }

    @Test
    fun askAgainLater_keepsTheBatchBuffered() = runTest {
        listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.RequestTimeout).forEach { status ->
            val engine = MockEngine { respondError(status) }

            val result = exporter(engine).export(listOf(FakeRecord("app.launched", emptyMap())))

            assertEquals(OperationResultCode.Failure, result, "$status should be retried")
        }
    }

    @Test
    fun permanentRejection_namesWhatGrafanaObjectedTo() = runTest {
        // ENG-82 (CARDS-CZ): five 400s in two seconds told us only "400". The
        // objection itself is in the body, so the next occurrence has to carry it.
        val objection = "entry out of order for stream {service_name=\"cards-client\"}: " +
            "x".repeat(900)
        val engine = MockEngine { respond(objection, HttpStatusCode.BadRequest) }

        val result = exporter(engine).export(listOf(FakeRecord("app.launched", emptyMap())))

        assertEquals(OperationResultCode.Success, result)
        val entry = logs.entries.single()
        assertTrue("400" in entry.message!!, "the status belongs in the message: ${entry.message}")
        val body = entry.context.extras["response_body"] as String
        assertTrue(body.startsWith("entry out of order for stream"), body)
        assertTrue(body.endsWith("...[+${objection.length - BODY_CAP} chars]"), body)
        assertEquals(1, entry.context.extras["records"])
    }

    @Test
    fun permanentRejection_bodyUnderTheCapIsNotMarkedTruncated() = runTest {
        val engine = MockEngine { respond("timestamp too old", HttpStatusCode.BadRequest) }

        exporter(engine).export(listOf(FakeRecord("app.launched", emptyMap())))

        assertEquals("timestamp too old", logs.entries.single().context.extras["response_body"])
    }

    @Test
    fun permanentRejection_withoutABodySaysSoRatherThanLookingLikeOne() = runTest {
        // A blank extra reads like "we logged the body and it was fine". It
        // means the gateway told us nothing, which is a different problem.
        val engine = MockEngine { respond("", HttpStatusCode.BadRequest) }

        val result = exporter(engine).export(listOf(FakeRecord("app.launched", emptyMap())))

        assertEquals(OperationResultCode.Success, result)
        assertEquals("<empty>", logs.entries.single().context.extras["response_body"])
    }

    @Test
    fun permanentRejection_alertsOnceThenDropsToWarn() = runTest {
        // A revoked token or a skewed clock refuses every batch for the life of
        // the process. One condition, one Sentry event; the repeats stay
        // readable as breadcrumbs carrying the running count.
        val engine = MockEngine { respondError(HttpStatusCode.BadRequest) }
        val exporter = exporter(engine)

        repeat(3) { exporter.export(listOf(FakeRecord("app.launched", emptyMap()))) }

        assertEquals(
            listOf(LogLevel.Error, LogLevel.Warn, LogLevel.Warn),
            logs.entries.map { it.level },
        )
        assertEquals(listOf(1, 2, 3), logs.entries.map { it.context.extras["rejected_batches"] })
    }

    @Test
    fun payload_capsOversizedAttributeValues() {
        // An iOS DarwinHttpRequestException message is an NSError dump running to
        // many hundreds of characters, and klogForwardingEnabled ships it as
        // exception_message. Nothing user-content-derived goes to the gateway
        // unbounded, whatever the gateway happens to accept today.
        val dump = "Error Domain=NSURLErrorDomain Code=-1001 " + "y".repeat(2_000)
        val record = FakeRecord("app.launched", mapOf("exception_message" to dump))

        val payload = exporter(MockEngine { respond("") }).payload(listOf(record))

        val attrs = payload["resourceLogs"]!!.jsonArray.single().jsonObject["scopeLogs"]!!
            .jsonArray.single().jsonObject["logRecords"]!!
            .jsonArray.single().jsonObject["attributes"]!!.jsonArray
        val value = attrs.single().jsonObject["value"]!!
            .jsonObject["stringValue"]!!.jsonPrimitive.content
        assertTrue(value.startsWith("Error Domain=NSURLErrorDomain Code=-1001"), value)
        assertTrue(value.endsWith("...[+${dump.length - ATTRIBUTE_CAP} chars]"), value)
        assertEquals(ATTRIBUTE_CAP, value.substringBefore("...[+").length)
    }

    private class CapturingLogTree : LogTree() {
        val entries = mutableListOf<LogEntry>()

        override fun log(entry: LogEntry): LogId? {
            entries += entry
            return null
        }
    }

    private companion object {
        const val BODY_CAP = 512
        const val ATTRIBUTE_CAP = 512
    }
}
