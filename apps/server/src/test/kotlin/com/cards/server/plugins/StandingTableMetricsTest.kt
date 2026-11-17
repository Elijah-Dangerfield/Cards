package com.dangerfield.cards.server.plugins

import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.data.InMemoryRoomService
import com.dangerfield.cards.server.domain.NoOpRoomStore
import com.dangerfield.cards.server.domain.NoOpStandingTableRepository
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomStore
import com.dangerfield.cards.server.domain.StandingTable
import com.dangerfield.cards.server.domain.StandingTableRepository
import com.dangerfield.cards.server.domain.UpdateProfileOutcome
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.sampleStandingTable
import com.dangerfield.cards.server.http.ClientContext
import com.dangerfield.cards.server.routes.InMemoryTestTableSessionService
import com.dangerfield.cards.server.routes.InMemoryTestWalletRepository
import com.dangerfield.cards.server.routes.RoomSocketTestAuth
import com.dangerfield.cards.server.routes.newRegistry
import com.dangerfield.cards.server.routes.roomRoutes
import com.dangerfield.cards.server.routes.sharePageRoutes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class StandingTableMetricsTest {
    private lateinit var reader: InMemoryMetricReader
    private lateinit var sdk: OpenTelemetrySdk
    private val now = Instant.parse("2026-11-15T12:00:00Z")
    private val clock = object : Clock { override fun now() = now }
    private val table = sampleStandingTable().copy(maxSeats = 2)
    private val profiles = MetricsProfiles(now)

    @Before fun installMetrics() {
        ServerMetrics.reset()
        resetGlobal()
        reader = InMemoryMetricReader.create()
        sdk = OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build()).build()
        GlobalOpenTelemetry.set(sdk)
        ServerMetrics.register()
    }

    @After fun closeMetrics() {
        ServerMetrics.reset()
        sdk.close()
        resetGlobal()
    }

    @Test fun shareRoutesCountEverySemanticOutcomeExactlyOnceWithBoundedLabels() = testApplication {
        val archived = table.copy(code = "BBBBBB", archivedAt = now)
        val tables = object : StandingTableRepository by NoOpStandingTableRepository {
            override suspend fun find(code: String) = listOf(table, archived).firstOrNull { it.code == code }
        }
        val rooms = InMemoryRoomService(clock)
        rooms.createWithCode("AAAAAA", table.ownerUserId!!, "Host", RoomSettings.forBuyIn(5000, 2))
        // The archived code still has a live sitting, but must never count as an ordinary invite.
        rooms.createWithCode(archived.code, table.ownerUserId!!, "Host", RoomSettings.forBuyIn(5000, 2))
        application {
            installRateLimits(AdminConfig(null, 30, 6))
            routing { sharePageRoutes(tables, rooms, profiles, clock) }
        }
        val cases = listOf(table.code to "standing", "AAAAAA" to "ordinary", "CCCCCC" to "unknown", archived.code to "archived", "IIIIII" to "invalid")
        for ((surface, suffix) in listOf("page" to "", "calendar" to "/calendar.ics", "preview" to "/preview.png")) {
            for ((code, outcome) in cases) {
                val response = client.get("/j/$code$suffix")
                val status = if (outcome == "invalid" || (surface == "calendar" && outcome != "standing")) HttpStatusCode.NotFound else HttpStatusCode.OK
                assertEquals(status, response.status)
            }
        }
        val points = points("cards.standing_tables.share.requests")
        assertEquals(15L, points.sumOf { it.value })
        assertEquals(15, points.size)
        for (surface in listOf("page", "calendar", "preview")) {
            for ((_, outcome) in cases) assertEquals(1L, count("cards.standing_tables.share.requests", outcome, surface))
        }
        assertTrue(points.all { it.attributes.asMap().keys.map { key -> key.key }.toSet() == setOf("surface", "outcome") })
    }

    @Test fun shareParserAndResourceStyleFailuresCountErrorBeforeGlobalHandler() = testApplication {
        val tables = object : StandingTableRepository by NoOpStandingTableRepository {
            override suspend fun find(code: String): StandingTable? {
                if (code == "AAAAAA") TimeZone.of("invalid-time-zone")
                throw ExceptionInInitializerError("Missing share resource")
            }
        }
        application {
            installStatusPages()
            installSerialization()
            installRateLimits(AdminConfig(null, 30, 6))
            routing { sharePageRoutes(tables, InMemoryRoomService(clock), profiles, clock) }
        }
        for ((surface, suffix) in listOf("page" to "", "calendar" to "/calendar.ics", "preview" to "/preview.png")) {
            assertEquals(HttpStatusCode.BadRequest, client.get("/j/AAAAAA$suffix").status)
            assertEquals(HttpStatusCode.InternalServerError, client.get("/j/BBBBBB$suffix").status)
            assertEquals(2L, count("cards.standing_tables.share.requests", "error", surface))
        }
        assertEquals(6L, points("cards.standing_tables.share.requests").sumOf { it.value })
    }

    @Test fun standingJoinsCountSuccessRefusalsAndErrorsWithoutCountingOrdinaryJoins() = testApplication {
        var archived = false
        var failRegularWrite = false
        val expensive = table.copy(code = "DDDDDD", buyIn = 1_000_000_000)
        val tables = object : StandingTableRepository by NoOpStandingTableRepository {
            override suspend fun find(code: String) = when (code) {
                table.code -> table.copy(archivedAt = now.takeIf { archived })
                expensive.code -> expensive
                else -> null
            }
            override suspend fun upsertRegular(code: String, userId: UserId, now: Instant) {
                check(!failRegularWrite) { "Regular write failed" }
            }
        }
        val rooms = InMemoryRoomService(clock, standingTables = tables)
        val wallets = InMemoryTestWalletRepository()
        application {
            installAuthenticationWithVerifier(RoomSocketTestAuth.verifier)
            installSerialization()
            installStatusPages()
            routing { roomRoutes(rooms, profiles, wallets, newRegistry(), InMemoryTestTableSessionService(wallets), tables, clock) }
        }
        val host = table.ownerUserId!!
        val friend = UserId(UUID.randomUUID())
        val third = UserId(UUID.randomUUID())
        suspend fun join(code: String, user: UserId) = client.post("/v1/rooms/$code/join") {
            header(HttpHeaders.Authorization, "Bearer ${RoomSocketTestAuth.jwt(user)}")
        }
        assertEquals(HttpStatusCode.OK, join(table.code, host).status)
        assertEquals(HttpStatusCode.OK, join(table.code, friend).status)
        assertEquals(HttpStatusCode.Conflict, join(table.code, third).status)
        assertEquals(HttpStatusCode.BadRequest, join(expensive.code, third).status)
        archived = true
        assertEquals(HttpStatusCode.NotFound, join(table.code, third).status)
        archived = false
        failRegularWrite = true
        assertEquals(HttpStatusCode.OK, join(table.code, host).status)
        profiles.unavailable = true
        assertEquals(HttpStatusCode.InternalServerError, join(table.code, host).status)
        profiles.unavailable = false
        assertEquals(HttpStatusCode.NotFound, join("ZZZZZZ", third).status)
        val expected = mapOf("already_joined" to 2L, "joined" to 1L, "full" to 1L, "insufficient_balance" to 1L, "archived" to 1L, "error" to 1L)
        for ((outcome, total) in expected) assertEquals(total, count("cards.standing_tables.join.requests", outcome), outcome)
        assertEquals(7L, points("cards.standing_tables.join.requests").sumOf { it.value })
        assertEquals(1L, count("cards.standing_tables.materializations", "created"))
        assertTrue(points("cards.standing_tables.join.requests").all { it.attributes.asMap().size == 1 })
    }

    @Test fun materializationCountsCreatedReusedAndFailureWithoutCountingCancellation() = runBlocking {
        val rooms = InMemoryRoomService(clock)
        val settings = RoomSettings.forBuyIn(5000, 2)
        rooms.createWithCode(table.code, table.ownerUserId!!, "Host", settings)
        rooms.createWithCode(table.code, table.ownerUserId!!, "Host", settings)
        val brokenStore = object : RoomStore by NoOpRoomStore { override suspend fun save(room: Room) = error("Store unavailable") }
        assertFailsWith<IllegalStateException> { InMemoryRoomService(clock, store = brokenStore).createWithCode("AAAAAA", table.ownerUserId!!, "Host", settings) }
        val cancelledStore = object : RoomStore by NoOpRoomStore { override suspend fun save(room: Room) { throw CancellationException("Request cancelled") } }
        assertFailsWith<CancellationException> { InMemoryRoomService(clock, store = cancelledStore).createWithCode("BBBBBB", table.ownerUserId!!, "Host", settings) }
        for (outcome in listOf("created", "reused", "error")) assertEquals(1L, count("cards.standing_tables.materializations", outcome))
        assertEquals(3L, points("cards.standing_tables.materializations").sumOf { it.value })
    }

    private fun points(name: String) = reader.collectAllMetrics().firstOrNull { it.name == name }?.longSumData?.points.orEmpty()
    private fun count(name: String, outcome: String, surface: String? = null): Long = points(name).filter {
        it.attributes.get(MetricAttrs.Outcome) == outcome && (surface == null || it.attributes.get(MetricAttrs.Surface) == surface)
    }.sumOf { it.value }

    private fun resetGlobal() {
        GlobalOpenTelemetry::class.java.getDeclaredMethod("resetForTest").apply { isAccessible = true }.invoke(null)
    }
}

private class MetricsProfiles(private val now: Instant) : ProfileRepository {
    var unavailable = false
    override suspend fun findById(userId: UserId) = Profile(userId, "Host", "", null, now, now)
    override suspend fun findOrCreate(userId: UserId, signupPlatform: ClientContext.Platform) =
        findById(userId).also { check(!unavailable) { "Profiles unavailable" } }
    override suspend fun update(userId: UserId, displayName: String?, avatarEmoji: String?, avatarBackgroundColor: String?, clearAvatarBackgroundColor: Boolean): UpdateProfileOutcome = error("Not used")
    override suspend fun delete(userId: UserId) = error("Not used")
    override suspend fun touchInstallId(userId: UserId, installId: UUID): UUID? = error("Not used")
    override suspend fun findInstallSiblings(installId: UUID, currentUserId: UserId): List<UserId> = emptyList()
}
