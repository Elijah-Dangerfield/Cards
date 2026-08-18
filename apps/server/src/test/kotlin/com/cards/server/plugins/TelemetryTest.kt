package com.dangerfield.cards.server.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.config.ObservabilityConfig
import com.dangerfield.cards.server.data.InMemoryRoomService
import com.dangerfield.cards.server.domain.FriendRepository
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.RespondResult
import com.dangerfield.cards.server.domain.SendRequestResult
import com.dangerfield.cards.server.domain.UpdateProfileOutcome
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.game.DefaultGameSessionRegistry
import com.dangerfield.cards.server.game.GameSession
import com.dangerfield.cards.server.game.NoOpSessionSnapshotStore
import com.dangerfield.cards.server.game.SeatOccupant
import com.dangerfield.cards.server.routes.InMemoryTestTableSessionService
import com.dangerfield.cards.server.routes.InMemoryTestWalletRepository
import com.dangerfield.cards.server.routes.matchmakingRoutes
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.UUID
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
    fun baggageCorrelationIds_areCopiedOntoSpans() = runTest {
        // Mirrors what installHttpServerTracing does per request: put the
        // correlation ids in OTel Baggage, then create spans inside that
        // context. The BaggageAttributeSpanProcessor must copy them onto the
        // span — and onto a nested child span — so `{ .session_id = X }` in
        // Tempo matches the whole trace tree, not just the HTTP root.
        val context = Baggage.current().toBuilder()
            .put("session_id", "sess-xyz")
            .put("install_id", "inst-abc")
            .build()
            .storeInContext(Context.current())

        withContext(context.asContextElement()) {
            withSpan("baggage_outer") {
                withSpan("baggage_inner") { }
            }
        }
        flushSpans()

        listOf("baggage_outer", "baggage_inner").forEach { name ->
            val span = exporter.finishedSpanItems.single { it.name == name }
            assertEquals("sess-xyz", span.attributes.get(AttributeKey.stringKey("session_id")), "$name session_id")
            assertEquals("inst-abc", span.attributes.get(AttributeKey.stringKey("install_id")), "$name install_id")
        }
    }

    @Test
    fun spansWithoutBaggage_haveNoCorrelationAttributes() = runTest {
        withSpan("no_baggage") { }
        flushSpans()

        val span = exporter.finishedSpanItems.single { it.name == "no_baggage" }
        assertEquals(null, span.attributes.get(AttributeKey.stringKey("session_id")))
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

    @Test
    fun gameSession_startHand_tracedStateCarriesStartHandSpanContext() = runTest {
        val session = GameSession(random = Random(seed = 42))
        val alice = SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false)
        val bob = SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false)

        session.startHand(listOf(alice, bob), RoomSettings.Default)
        flushSpans()

        val startSpan = exporter.finishedSpanItems.first { it.name == "start_hand" }
        val traced = session.tracedState.value
        assertNotNull(traced, "startHand should publish a traced state snapshot")
        assertEquals(
            startSpan.spanContext.spanId,
            traced.originSpanContext.spanId,
            "the published state should carry the start_hand span context so the ws_send snapshot fan-out can link back",
        )
    }

    @Test
    fun gameSession_applyIntent_tracedStateCarriesStateMutateSpanContext() = runTest {
        val session = GameSession(random = Random(seed = 42))
        val alice = SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false)
        val bob = SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false)
        session.startHand(listOf(alice, bob), RoomSettings.Default)
        val acting = session.state.value!!.actingSeatIndex!!
        val actor = session.state.value!!.seats.first { it.index == acting }

        session.applyIntent(
            actorUserId = actor.playerId!!,
            intent = PlayerIntent.Fold(seatIndex = acting),
            clientNonce = "snapshot-link-1",
        )
        flushSpans()

        val mutateSpan = exporter.finishedSpanItems.first { it.name == "state_mutate" }
        val traced = session.tracedState.value
        assertNotNull(traced, "applyIntent should publish a traced state snapshot")
        assertEquals(
            mutateSpan.spanContext.spanId,
            traced.originSpanContext.spanId,
            "the post-intent state should carry the state_mutate span context (which sits under submit_intent)",
        )
        assertEquals(
            mutateSpan.spanContext.traceId,
            traced.originSpanContext.traceId,
            "the linked snapshot must share the submit_intent trace",
        )
    }

    @Test
    fun matchmakingFindRoute_emitsMatchmakingFindSpan_withDensityAttrs() = runTest {
        val issuer = "https://test-project.supabase.co/auth/v1"
        val secret = "0123456789abcdef0123456789abcdef0123456789abcdef"
        val verifier = JWT.require(Algorithm.HMAC256(secret))
            .withIssuer(issuer).withAudience("authenticated").build()
        fun jwt(sub: UUID) = JWT.create()
            .withIssuer(issuer).withAudience("authenticated").withSubject(sub.toString())
            .withIssuedAt(Date()).withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256(secret))

        val clock = object : kotlin.time.Clock {
            private var t = 1_700_000_000_000L
            @OptIn(kotlin.time.ExperimentalTime::class)
            override fun now(): kotlin.time.Instant =
                kotlin.time.Instant.fromEpochMilliseconds(t).also { t += 1_000 }
        }
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))

        testApplication {
            application {
                installSerialization()
                installRateLimits(AdminConfig(apiToken = null, orphanAnonTtlDays = 30, staleRoomTtlHours = 6))
                installStatusPages()
                installAuthenticationWithVerifier(verifier)
                val registry = DefaultGameSessionRegistry(NoOpSessionSnapshotStore(), kotlin.time.Clock.System)
                routing {
                    matchmakingRoutes(
                        rooms, NoBlocksFriends, FixedProfiles, registry,
                        InMemoryTestTableSessionService(InMemoryTestWalletRepository()),
                        StubEquipment, StubProgression,
                        InMemoryTestWalletRepository(),
                    )
                }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            client.post("/v1/matchmaking/find") {
                header(HttpHeaders.Authorization, "Bearer ${jwt(UUID.randomUUID())}")
                contentType(ContentType.Application.Json)
                setBody("""{"minBuyIn":1000,"maxBuyIn":1000}""")
            }
        }
        flushSpans()

        val span = exporter.finishedSpanItems.firstOrNull { it.name == "matchmaking_find" }
        assertNotNull(span, "expected a matchmaking_find span; got ${exporter.finishedSpanItems.map { it.name }}")
        assertEquals(true, span.attributes.get(SpanAttrs.MatchmakingCreated), "first searcher opened a table")
        assertEquals(1L, span.attributes.get(SpanAttrs.MatchmakingHumans), "one human seated")
        assertEquals(1_000L, span.attributes.get(SpanAttrs.MatchmakingBuyIn))
    }

    private fun flushSpans() {
        sdk.sdkTracerProvider.forceFlush().join(1, TimeUnit.SECONDS)
    }

    private fun flushLogs() {
        sdk.sdkLoggerProvider.forceFlush().join(1, TimeUnit.SECONDS)
    }

    private object NoBlocksFriends : FriendRepository {
        override suspend fun sendRequest(from: UserId, to: UserId): SendRequestResult = SendRequestResult.Sent
        override suspend fun accept(me: UserId, other: UserId): RespondResult = RespondResult.Ok
        override suspend fun decline(me: UserId, other: UserId): RespondResult = RespondResult.Ok
        override suspend fun block(me: UserId, other: UserId) = Unit
        override suspend fun listFriends(userId: UserId): List<UserId> = emptyList()
        override suspend fun listBlockedUserIds(userId: UserId): Set<UserId> = emptySet()
        override suspend fun listIncomingRequests(userId: UserId): List<UserId> = emptyList()
        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private object StubEquipment : com.dangerfield.cards.server.domain.EquipmentRepository {
        override suspend fun listEquipped(userId: UserId) =
            emptyList<com.dangerfield.cards.server.domain.EquippedItem>()
        @OptIn(kotlin.time.ExperimentalTime::class)
        override suspend fun equip(userId: UserId, productId: String, newUpdatedAt: kotlin.time.Instant) = error("unused")
        @OptIn(kotlin.time.ExperimentalTime::class)
        override suspend fun unequip(userId: UserId, productId: String, opUpdatedAt: kotlin.time.Instant) = null
    }

    private object StubProgression : com.dangerfield.cards.server.domain.ProgressionRepository {
        override suspend fun findOrCreateResult(userId: UserId) = error("unused")
        override suspend fun find(userId: UserId): com.dangerfield.cards.server.domain.UserProgression? = null
        override suspend fun applyXp(
            userId: UserId,
            idempotencyKey: String,
            deltaXp: Long,
            source: String,
            mode: String,
            handId: String?,
            wasBoosted: Boolean,
        ) = error("unused")
        override suspend fun recentEvents(userId: UserId, limit: Int) =
            emptyList<com.dangerfield.cards.server.domain.XpEvent>()
        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private object FixedProfiles : ProfileRepository {
        override suspend fun findById(userId: UserId): Profile? = null
        override suspend fun findOrCreate(userId: UserId): Profile = Profile(
            userId = userId,
            displayName = "P-${userId.value.toString().take(4)}",
            avatarEmoji = "🃏",
            avatarBackgroundColor = null,
            createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
            updatedAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        )
        override suspend fun update(
            userId: UserId,
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ): UpdateProfileOutcome = error("unused")
        override suspend fun delete(userId: UserId) = Unit
        override suspend fun touchInstallId(userId: UserId, installId: UUID): UUID? = null
        override suspend fun findInstallSiblings(installId: UUID, currentUserId: UserId): List<UserId> = emptyList()
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
