package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.PlayStyleHandSummary
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.storage.db.PlayStyleDao
import com.dangerfield.cards.libraries.cards.storage.db.PlayStyleEntity
import com.dangerfield.cards.libraries.cards.storage.db.PlayStyleEventDao
import com.dangerfield.cards.libraries.cards.storage.db.PlayStyleEventEntity
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.networking.InternalNetworkingApi
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pins [PlayStyleRepositoryImpl] — sync reconciliation and the gated opponent
 * fetch — via a Ktor MockEngine. Mirrors [ProgressionRepositoryImplSyncTest].
 *
 * What's pinned:
 *  - recordHand appends one unsynced outbox row.
 *  - Empty pending still syncs (the hydrate that caches cross-device axes).
 *  - Applied / AlreadyApplied → rows marked synced; axes cached. Unknown → row
 *    stays unsynced. Network failure → Result.failure, rows stay unsynced.
 *  - getStyleFor caches a success in-session; a failure is NOT cached, so a
 *    later fetch retries and can succeed.
 */
class PlayStyleRepositoryImplSyncTest : CoroutineTest() {

    @Test
    fun recordHand_appendsOneUnsyncedRow() = runUnitTest {
        val events = FakePlayStyleEventDao()
        val repo = build(events = events) { error("no network in this test") }

        repo.recordHand(handSummary(handId = "7", vpip = true))

        val rows = events.getUnsynced()
        assertEquals(1, rows.size)
        assertTrue(rows.single().vpip)
        assertFalse(rows.single().synced)
    }

    @Test
    fun emptyPending_stillSyncs_andCachesAxes() = runUnitTest {
        val style = FakePlayStyleDao()
        var hits = 0
        val repo = build(style = style) {
            hits++
            respondJson("""{"schemaVersion":1,"axes":{"tight":0.8,"aggro":0.3,"bluff":0.1,"patient":0.7,"sampleSize":42},"results":[]}""")
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertEquals(1, hits, "empty batch still triggers the hydrate sync")
        val cached = style.get()!!
        assertEquals(0.8, cached.tight, 1e-9)
        assertEquals(42L, cached.sampleSize)
    }

    @Test
    fun appliedEvents_markedSynced() = runUnitTest {
        val events = FakePlayStyleEventDao(row("k1"), row("k2"))
        val repo = build(events = events) {
            respondJson(
                """
                {"schemaVersion":1,"axes":{"tight":0.5,"aggro":0.5,"bluff":0.0,"patient":0.5,"sampleSize":2},
                 "results":[{"idempotencyKey":"k1","outcome":"Applied"},
                            {"idempotencyKey":"k2","outcome":"AlreadyApplied"}]}
                """.trimIndent(),
            )
        }

        repo.sync()

        assertTrue(events.getUnsynced().isEmpty(), "Applied + AlreadyApplied are marked synced")
    }

    @Test
    fun unknownOutcome_leavesRowUnsynced() = runUnitTest {
        val events = FakePlayStyleEventDao(row("k1"))
        val repo = build(events = events) {
            respondJson(
                """{"schemaVersion":1,"axes":{"tight":0.0,"aggro":0.0,"bluff":0.0,"patient":0.0,"sampleSize":0},
                    "results":[{"idempotencyKey":"k1","outcome":"SomethingNewer"}]}""",
            )
        }

        repo.sync()

        assertEquals(1, events.getUnsynced().size, "an unknown outcome leaves the row for a newer client to retry")
    }

    @Test
    fun networkFailure_isFailure_andLeavesRowsUnsynced() = runUnitTest {
        val events = FakePlayStyleEventDao(row("k1"))
        val style = FakePlayStyleDao()
        val repo = build(style = style, events = events) {
            respond(ByteReadChannel("boom"), HttpStatusCode.InternalServerError)
        }

        val result = repo.sync()

        assertTrue(result.isFailure)
        assertEquals(1, events.getUnsynced().size, "a failed sync keeps rows pending")
        assertNull(style.get(), "a failed sync doesn't cache axes")
    }

    @Test
    fun getStyleFor_cachesSuccess_inSession() = runUnitTest {
        var hits = 0
        val repo = build {
            hits++
            respondJson("""{"schemaVersion":1,"axes":{"tight":0.6,"aggro":0.4,"bluff":0.2,"patient":0.5,"sampleSize":30}}""")
        }

        val first = repo.getStyleFor("opp-1").getOrNull()
        val second = repo.getStyleFor("opp-1").getOrNull()

        assertEquals(30L, first?.sampleSize)
        assertEquals(first, second)
        assertEquals(1, hits, "the second lookup is served from the in-session cache")
    }

    @Test
    fun getStyleFor_doesNotCacheFailure() = runUnitTest {
        var failMode = true
        val repo = build {
            if (failMode) {
                respond(ByteReadChannel("boom"), HttpStatusCode.InternalServerError)
            } else {
                respondJson("""{"schemaVersion":1,"axes":{"tight":0.1,"aggro":0.9,"bluff":0.3,"patient":0.1,"sampleSize":99}}""")
            }
        }

        assertTrue(repo.getStyleFor("opp-1").isFailure)
        failMode = false
        // A failed fetch must not be cached — the retry after recovery succeeds.
        assertEquals(99L, repo.getStyleFor("opp-1").getOrNull()?.sampleSize)
    }

    // --- helpers ---

    private fun handSummary(
        handId: String,
        mode: XpMode = XpMode.BOTS,
        vpip: Boolean = false,
    ) = PlayStyleHandSummary(
        handId = handId,
        mode = mode,
        inBlind = false,
        vpip = vpip,
        pfr = false,
        preflopFold = false,
        aggressiveActionCount = 0,
        callActionCount = 0,
        wentToShowdown = false,
        showdownBluff = false,
    )

    private fun row(key: String) = PlayStyleEventEntity(
        idempotencyKey = key,
        synced = false,
        mode = "BOTS",
        inBlind = false,
        vpip = true,
        pfr = false,
        preflopFold = false,
        aggressiveActionCount = 1,
        callActionCount = 0,
        wentToShowdown = false,
        showdownBluff = false,
        createdAtEpochMs = 1_000L,
    )

    private fun build(
        style: FakePlayStyleDao = FakePlayStyleDao(),
        events: FakePlayStyleEventDao = FakePlayStyleEventDao(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): PlayStyleRepositoryImpl {
        val httpClient = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; coerceInputValues = true })
            }
            expectSuccess = true
        }
        @OptIn(InternalNetworkingApi::class)
        val networkClient = object : NetworkClient {
            override val client: HttpClient = httpClient
            override val authenticatedClient: HttpClient = httpClient
            override suspend fun awaitAuthReady() = Unit
        }
        return PlayStyleRepositoryImpl(
            playStyleDao = style,
            playStyleEventDao = events,
            networkClient = networkClient,
            appScope = AppCoroutineScope(dispatchers),
            clock = FixedClock,
        )
    }

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
    )

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
    }

    private class FakePlayStyleDao : PlayStyleDao {
        private var entity: PlayStyleEntity? = null
        private val flow = MutableStateFlow<PlayStyleEntity?>(null)
        override fun observe(): Flow<PlayStyleEntity?> = flow.asStateFlow()
        override suspend fun get(): PlayStyleEntity? = entity
        override suspend fun set(entity: PlayStyleEntity) {
            this.entity = entity
            flow.value = entity
        }
        override suspend fun deleteAll() {
            entity = null
            flow.value = null
        }
    }

    private class FakePlayStyleEventDao(vararg seed: PlayStyleEventEntity) : PlayStyleEventDao {
        private val rows = seed.toMutableList()
        override suspend fun insertAll(events: List<PlayStyleEventEntity>) { rows += events }
        override suspend fun getUnsynced(): List<PlayStyleEventEntity> = rows.filter { !it.synced }
        override suspend fun markSynced(keys: List<String>) {
            val set = keys.toSet()
            for (i in rows.indices) if (rows[i].idempotencyKey in set) rows[i] = rows[i].copy(synced = true)
        }
        override suspend fun deleteAll() = rows.clear()
    }
}
