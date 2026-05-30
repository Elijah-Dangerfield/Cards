package com.dangerfield.cards.server.plugins

import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.config.ObservabilityConfig
import com.dangerfield.cards.server.game.GameSession
import com.dangerfield.cards.server.game.SeatOccupant
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.BeforeClass
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the OTel wiring contract: the SDK builds for both exporter
 * modes, [withSpan] emits properly-attributed spans, and the gameplay
 * path produces the expected child spans.
 *
 * `GlobalOpenTelemetry.set(...)` is set-once per classloader; this is
 * the only test class in the suite that touches the global slot, so a
 * single companion-init install is safe. Other tests run alongside
 * with an installed SDK that pipes their spans into the same in-memory
 * exporter — `@Before` clears it so each test asserts on its own slice.
 */
class TelemetryTest {

    @Before
    fun resetExporter() {
        exporter.reset()
        logExporter.reset()
    }

    @org.junit.After
    fun resetMetrics() {
        // Each metric test re-registers the gauge with a known value;
        // tear down so the next test starts from a clean slate. Other
        // tests don't touch ServerMetrics so this is a no-op for them.
        ServerMetrics.reset()
    }

    @Test
    fun buildOpenTelemetrySdk_withNoEndpoint_returnsUsableSdk() {
        val sdk = buildOpenTelemetrySdk(
            ObservabilityConfig(
                otlpEndpoint = null,
                otlpHeaders = null,
                serviceName = "test-server",
                environment = "test",
                release = null,
            ),
        )

        sdk.getTracer("smoke").spanBuilder("noop").startSpan().end()
        sdk.close()
    }

    @Test
    fun buildOpenTelemetrySdk_resourceCarriesServiceAndEnvAttrs() {
        val exporter = InMemorySpanExporter.create()
        val sdk = buildOpenTelemetrySdk(
            ObservabilityConfig(
                otlpEndpoint = null,
                otlpHeaders = null,
                serviceName = "cards-server",
                environment = "test",
                release = "abc123",
            ),
            spanExporter = exporter,
        )
        sdk.getTracer("attrs").spanBuilder("noop").startSpan().end()
        sdk.sdkTracerProvider.forceFlush().join(1, TimeUnit.SECONDS)

        val span = exporter.finishedSpanItems.single()
        val resourceAttrs = span.resource.attributes
        assertEquals("cards-server", resourceAttrs.get(AttributeKey.stringKey("service.name")))
        assertEquals("test", resourceAttrs.get(AttributeKey.stringKey("deployment.environment")))
        assertEquals("abc123", resourceAttrs.get(AttributeKey.stringKey("service.version")))
        sdk.close()
    }

    @Test
    fun withSpan_emitsSpanWithAttributes() = runTest {
        withSpan(
            name = "test_span",
            configure = {
                setAttribute(SpanAttrs.RoomCode, "AB1234")
                setAttribute(SpanAttrs.UserId, "user-1")
            },
        ) {
            // no-op body
        }
        flushSpans()

        val span = exporter.finishedSpanItems.single { it.name == "test_span" }
        assertEquals("AB1234", span.attributes.get(SpanAttrs.RoomCode))
        assertEquals("user-1", span.attributes.get(SpanAttrs.UserId))
    }

    @Test
    fun withSpan_propagatesContextToChild() = runTest {
        withSpan(name = "outer") {
            withSpan(name = "inner") { }
        }
        flushSpans()

        val outer = exporter.finishedSpanItems.single { it.name == "outer" }
        val inner = exporter.finishedSpanItems.single { it.name == "inner" }
        assertEquals(
            outer.spanContext.spanId,
            inner.parentSpanContext.spanId,
            "inner span should be parented to outer via the coroutine context element",
        )
    }

    @Test
    fun logger_inSpanContext_attachesTraceIdToLogRecord() = runTest {
        withSpan("log_test") {
            LoggerFactory.getLogger(TelemetryTest::class.java)
                .info("hello from inside a span")
        }
        flushLogs()
        flushSpans()

        val span = exporter.finishedSpanItems.single { it.name == "log_test" }
        val record = logExporter.finishedLogRecordItems
            .firstOrNull { it.bodyValue?.asString() == "hello from inside a span" }
        assertNotNull(record, "expected the log record to flow through the OTel appender")
        assertEquals(
            span.spanContext.traceId,
            record.spanContext.traceId,
            "log record should carry the active span's trace_id for trace↔log correlation",
        )
    }

    @Test
    fun gameSession_applyIntent_emitsEngineApplyIntentSpanWithIntentAttrs() = runTest {
        val session = GameSession(random = Random(seed = 42))
        val alice = SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false)
        val bob = SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false)
        session.startHand(listOf(alice, bob), RoomSettings.Default)
        val acting = session.state.value!!.actingSeatIndex!!
        val actor = session.state.value!!.seats.first { it.index == acting }

        session.applyIntent(
            actorUserId = actor.playerId!!,
            intent = PlayerIntent.Fold(seatIndex = acting),
            clientNonce = "n1",
        )
        flushSpans()

        val engineSpan = exporter.finishedSpanItems.firstOrNull { it.name == "engine.apply_intent" }
        assertNotNull(engineSpan, "expected an engine.apply_intent span; got ${exporter.finishedSpanItems.map { it.name }}")
        assertEquals("Fold", engineSpan.attributes.get(SpanAttrs.IntentType))
        assertEquals(1L, engineSpan.attributes.get(SpanAttrs.HandNumber))
        assertTrue(engineSpan.attributes.get(SpanAttrs.SessionId)?.isNotBlank() == true)
    }

    @Test
    fun gameSession_applyIntent_emitsValidateAndStateMutateSubSpans_onHappyPath() = runTest {
        val session = GameSession(random = Random(seed = 42))
        val alice = SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false)
        val bob = SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false)
        session.startHand(listOf(alice, bob), RoomSettings.Default)
        val acting = session.state.value!!.actingSeatIndex!!
        val actor = session.state.value!!.seats.first { it.index == acting }

        session.applyIntent(
            actorUserId = actor.playerId!!,
            intent = PlayerIntent.Fold(seatIndex = acting),
            clientNonce = "validate-and-mutate",
        )
        flushSpans()

        val validate = exporter.finishedSpanItems.firstOrNull { it.name == "validate_intent" }
        val mutate = exporter.finishedSpanItems.firstOrNull { it.name == "state_mutate" }
        assertNotNull(validate, "expected a validate_intent span; got ${exporter.finishedSpanItems.map { it.name }}")
        assertNotNull(mutate, "expected a state_mutate span; got ${exporter.finishedSpanItems.map { it.name }}")
        assertEquals("Fold", validate.attributes.get(SpanAttrs.IntentType))
        assertEquals("Fold", mutate.attributes.get(SpanAttrs.IntentType))
        assertEquals(1L, validate.attributes.get(SpanAttrs.HandNumber))
        assertEquals(1L, mutate.attributes.get(SpanAttrs.HandNumber))
    }

    @Test
    fun gameSession_startHand_emitsStartHandSpan() = runTest {
        val session = GameSession(random = Random(seed = 42))
        val alice = SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false)
        val bob = SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false)

        session.startHand(listOf(alice, bob), RoomSettings.Default)
        flushSpans()

        val span = exporter.finishedSpanItems.firstOrNull { it.name == "start_hand" }
        assertNotNull(span, "expected a start_hand span; got ${exporter.finishedSpanItems.map { it.name }}")
        assertEquals(2L, span.attributes.get(SpanAttrs.OccupantsCount))
        assertTrue(span.attributes.get(SpanAttrs.SessionId)?.isNotBlank() == true)
    }

    @Test
    fun gameSession_requestNextHand_emitsRequestNextHandSpan() = runTest {
        val session = GameSession(random = Random(seed = 42))
        val alice = SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false)
        val bob = SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false)
        session.startHand(listOf(alice, bob), RoomSettings.Default)
        // Drive the hand to completion so requestNextHand is accepted.
        // Fold-around in heads-up = winner-by-default → BettingRound.Complete.
        while (session.state.value!!.street != com.dangerfield.cards.libraries.gameplay.BettingRound.Complete) {
            val current = session.state.value!!
            val acting = current.actingSeatIndex ?: break
            val actor = current.seats.first { it.index == acting }
            session.applyIntent(
                actorUserId = actor.playerId!!,
                intent = PlayerIntent.Fold(seatIndex = acting),
                clientNonce = "step-$acting",
            )
        }
        exporter.reset()

        session.requestNextHand(actorUserId = "alice", clientNonce = "next-1")
        flushSpans()

        val nextHand = exporter.finishedSpanItems.firstOrNull { it.name == "request_next_hand" }
        assertNotNull(nextHand, "expected a request_next_hand span; got ${exporter.finishedSpanItems.map { it.name }}")
        // The previous hand's number, since hand_number is read before
        // the engine bumps it inside startHandLocked.
        assertEquals(1L, nextHand.attributes.get(SpanAttrs.HandNumber))
        assertTrue(nextHand.attributes.get(SpanAttrs.SessionId)?.isNotBlank() == true)
    }

    @Test
    fun gameSession_applyIntent_skipsStateMutateSpan_whenValidationRejects() = runTest {
        val session = GameSession(random = Random(seed = 42))
        val alice = SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false)
        val bob = SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false)
        session.startHand(listOf(alice, bob), RoomSettings.Default)
        val acting = session.state.value!!.actingSeatIndex!!
        val nonActing = if (acting == 0) 1 else 0
        val nonActor = session.state.value!!.seats.first { it.index == nonActing }

        // Not-your-turn rejection — validate_intent fires but the engine
        // span + state_mutate must not.
        session.applyIntent(
            actorUserId = nonActor.playerId!!,
            intent = PlayerIntent.Fold(seatIndex = nonActing),
            clientNonce = "wrong-turn",
        )
        flushSpans()

        assertNotNull(
            exporter.finishedSpanItems.firstOrNull { it.name == "validate_intent" },
            "validation span must fire even on reject",
        )
        assertTrue(
            exporter.finishedSpanItems.none { it.name == "state_mutate" },
            "state_mutate must not fire on a validation reject; got ${exporter.finishedSpanItems.map { it.name }}",
        )
    }

    @Test
    fun gameSession_startHand_eventsCarryStartHandSpanContext() = runTest {
        val session = GameSession(random = Random(seed = 42))
        val alice = SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false)
        val bob = SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false)

        session.startHand(listOf(alice, bob), RoomSettings.Default)
        flushSpans()

        val startSpan = exporter.finishedSpanItems.first { it.name == "start_hand" }
        val emitted = session.events.replayCache
        assertTrue(emitted.isNotEmpty(), "start_hand should buffer its opening events")
        assertTrue(
            emitted.all { it.originSpanContext.spanId == startSpan.spanContext.spanId },
            "every start_hand event should carry the start_hand span context so the ws_send fan-out can link back",
        )
    }

    @Test
    fun gameSession_applyIntent_eventsCarryStateMutateSpanContext() = runTest {
        val session = GameSession(random = Random(seed = 42))
        val alice = SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false)
        val bob = SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false)
        session.startHand(listOf(alice, bob), RoomSettings.Default)
        val acting = session.state.value!!.actingSeatIndex!!
        val actor = session.state.value!!.seats.first { it.index == acting }

        session.applyIntent(
            actorUserId = actor.playerId!!,
            intent = PlayerIntent.Fold(seatIndex = acting),
            clientNonce = "link-1",
        )
        flushSpans()

        val mutateSpan = exporter.finishedSpanItems.first { it.name == "state_mutate" }
        val fromMutate = session.events.replayCache
            .filter { it.originSpanContext.spanId == mutateSpan.spanContext.spanId }
        assertTrue(
            fromMutate.isNotEmpty(),
            "the fold's resolved events should carry the state_mutate span context; got ${session.events.replayCache.map { it.event::class.simpleName }}",
        )
        assertTrue(
            fromMutate.all { it.originSpanContext.traceId == mutateSpan.spanContext.traceId },
            "linked events must share the submit_intent trace",
        )
    }

    private fun flushSpans() {
        sdk.sdkTracerProvider.forceFlush().join(1, TimeUnit.SECONDS)
    }

    private fun flushLogs() {
        sdk.sdkLoggerProvider.forceFlush().join(1, TimeUnit.SECONDS)
    }

    companion object {
        private val exporter = InMemorySpanExporter.create()
        private val logExporter = InMemoryLogRecordExporter.create()
        private val metricReader = InMemoryMetricReader.create()
        private val sdk = buildOpenTelemetrySdk(
            ObservabilityConfig(
                otlpEndpoint = null,
                otlpHeaders = null,
                serviceName = "cards-server-test",
                environment = "test",
                release = null,
            ),
            spanExporter = exporter,
            logRecordExporter = logExporter,
            metricReader = metricReader,
        )

        @BeforeClass
        @JvmStatic
        fun installGlobalOpenTelemetry() {
            // `GlobalOpenTelemetry.set` is one-shot: it can only be
            // called once before any `get` call. Other tests in the
            // suite that exercise the gameplay path implicitly hit
            // `GlobalOpenTelemetry.getTracer(...)` (via withSpan), which
            // initialises the global to noop and locks `set` out. The
            // SDK ships a package-private `resetForTest` for exactly
            // this scenario; reflection bypasses the access check.
            resetGlobalOpenTelemetry()
            GlobalOpenTelemetry.set(sdk)
            // Logback already has the `OpenTelemetryAppender` registered
            // (logback.xml on the test classpath), but it buffers
            // records until install() points it at a live SDK. Wire it
            // here so log records emitted during tests flow into
            // `logExporter`.
            OpenTelemetryAppender.install(sdk)
        }

        private fun resetGlobalOpenTelemetry() {
            val method = GlobalOpenTelemetry::class.java.getDeclaredMethod("resetForTest")
            method.isAccessible = true
            method.invoke(null)
        }
    }

    @Test
    fun serverMetrics_anonOrphansGauge_emitsRecordedValueWithTtlAttribute() {
        ServerMetrics.register()
        ServerMetrics.recordAnonOrphanCandidates(count = 17L, ttlDays = 30L)

        val metrics = metricReader.collectAllMetrics()
        val gauge = metrics.firstOrNull { it.name == "cards.anon_orphans.over_ttl" }
        assertNotNull(gauge, "expected cards.anon_orphans.over_ttl gauge; got ${metrics.map { it.name }}")
        val point = gauge.longGaugeData.points.single()
        assertEquals(17L, point.value)
        assertEquals(30L, point.attributes.get(MetricAttrs.OrphanSweepTtlDays))
    }

    @Test
    fun serverMetrics_anonOrphansGauge_silentBeforeFirstRecord() {
        ServerMetrics.register()
        // No recordAnonOrphanCandidates call → callback should skip
        // emission so a freshly-booted server doesn't report a fake "0
        // orphans" sample before the first sweep runs.
        val metrics = metricReader.collectAllMetrics()
        val gauge = metrics.firstOrNull { it.name == "cards.anon_orphans.over_ttl" }
        assertTrue(
            gauge == null || gauge.longGaugeData.points.isEmpty(),
            "expected no measurements before the first sweep run; got ${gauge?.longGaugeData?.points}",
        )
    }

    @Test
    fun serverMetrics_anonOrphansGauge_reflectsLatestRecordedValue() {
        ServerMetrics.register()
        ServerMetrics.recordAnonOrphanCandidates(count = 5L, ttlDays = 30L)
        ServerMetrics.recordAnonOrphanCandidates(count = 12L, ttlDays = 30L)

        val metrics = metricReader.collectAllMetrics()
        val gauge = metrics.first { it.name == "cards.anon_orphans.over_ttl" }
        val point = gauge.longGaugeData.points.single()
        assertEquals(12L, point.value)
    }

    @Test
    fun serverMetrics_register_isIdempotent_doesNotDoubleCount() {
        ServerMetrics.register()
        ServerMetrics.register()
        ServerMetrics.recordAnonOrphanCandidates(count = 4L, ttlDays = 30L)

        val metrics = metricReader.collectAllMetrics()
        val gauge = metrics.first { it.name == "cards.anon_orphans.over_ttl" }
        // Exactly one point — a double-register would have produced two.
        val point = gauge.longGaugeData.points.single()
        assertEquals(4L, point.value)
    }

    @Test
    fun parseOtlpHeaders_percentDecodesEachValue() {
        // Grafana Cloud hands the auth header as `Basic%20<base64>`. The
        // SDK consumer needs the literal-space form, so the parser must
        // URL-decode values. Skipping that decode means every request
        // ships with header `Basic%20…` and the gateway 401s.
        val parsed = parseOtlpHeaders("Authorization=Basic%20abc%3D%3D,X-Other=hello%20world")

        assertEquals(
            listOf(
                "Authorization" to "Basic abc==",
                "X-Other" to "hello world",
            ),
            parsed,
        )
    }

    @Test
    fun parseOtlpHeaders_handlesEmptyAndMalformedInputs() {
        assertEquals(emptyList(), parseOtlpHeaders(null))
        assertEquals(emptyList(), parseOtlpHeaders(""))
        assertEquals(emptyList(), parseOtlpHeaders("   "))
        // No `=` → dropped, not crashed.
        assertEquals(emptyList(), parseOtlpHeaders("nothingHere"))
    }
}
