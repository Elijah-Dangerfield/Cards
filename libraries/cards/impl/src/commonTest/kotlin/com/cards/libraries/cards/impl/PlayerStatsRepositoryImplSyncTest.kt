package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.PlayerStatHandSummary
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.storage.db.PlayerStatEventDao
import com.dangerfield.cards.libraries.cards.storage.db.PlayerStatEventEntity
import com.dangerfield.cards.libraries.cards.storage.db.PlayerStatsDao
import com.dangerfield.cards.libraries.cards.storage.db.PlayerStatsEntity
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
 * Pins [PlayerStatsRepositoryImpl] — the outbox + sync reconciliation — via a
 * Ktor MockEngine. Mirrors [PlayStyleRepositoryImplSyncTest].
 *
 * What's pinned:
 *  - recordHand appends one unsynced outbox row.
 *  - Empty pending still syncs (the hydrate that caches a cross-device snapshot).
 *  - Applied / AlreadyApplied → rows marked synced; snapshot cached (incl. the
 *    per-bot wins map round-tripping through the JSON column). Unknown → row
 *    stays unsynced. Network failure → Result.failure, rows stay unsynced.
 */
class PlayerStatsRepositoryImplSyncTest : CoroutineTest() {

    @Test
    fun recordHand_appendsOneUnsyncedRow() = runUnitTest {
        val events = FakePlayerStatEventDao()
        val repo = build(events = events) { error("no network in this test") }

        repo.recordHand(handSummary(handId = "7", won = true, vsBot = true, beatenBotId = "Jane", noBustStreak = 3))

        val rows = events.getUnsynced()
        assertEquals(1, rows.size)
        assertTrue(rows.single().won)
        assertEquals("Jane", rows.single().beatenBotId)
        assertEquals(3L, rows.single().noBustStreak)
        assertFalse(rows.single().synced)
    }

    @Test
    fun emptyPending_stillSyncs_andCachesSnapshot() = runUnitTest {
        val stats = FakePlayerStatsDao()
        var hits = 0
        val repo = build(stats = stats) {
            hits++
            respondJson(
                """
                {"schemaVersion":1,"stats":{"handsPlayed":42,"handsWon":10,"handsFolded":12,
                 "handsLostAtShowdown":5,"botHandsPlayed":40,"currentNoBustStreak":4,
                 "bestNoBustStreak":9,"perBotWins":{"Jane":6,"David":4}},"results":[]}
                """.trimIndent(),
            )
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertEquals(1, hits, "empty batch still triggers the hydrate sync")
        val cached = repo.getStats()!!
        assertEquals(42L, cached.handsPlayed)
        assertEquals(4L, cached.currentNoBustStreak)
        assertEquals(9L, cached.bestNoBustStreak)
        assertEquals(mapOf("Jane" to 6L, "David" to 4L), cached.perBotWins)
    }

    @Test
    fun appliedEvents_markedSynced() = runUnitTest {
        val events = FakePlayerStatEventDao(row("k1"), row("k2"))
        val repo = build(events = events) {
            respondJson(
                """
                {"schemaVersion":1,"stats":{"handsPlayed":2,"handsWon":0,"handsFolded":0,
                 "handsLostAtShowdown":0,"botHandsPlayed":0,"currentNoBustStreak":0,
                 "bestNoBustStreak":0,"perBotWins":{}},
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
        val events = FakePlayerStatEventDao(row("k1"))
        val repo = build(events = events) {
            respondJson(
                """
                {"schemaVersion":1,"stats":{"handsPlayed":0,"handsWon":0,"handsFolded":0,
                 "handsLostAtShowdown":0,"botHandsPlayed":0,"currentNoBustStreak":0,
                 "bestNoBustStreak":0,"perBotWins":{}},
                 "results":[{"idempotencyKey":"k1","outcome":"SomethingNewer"}]}
                """.trimIndent(),
            )
        }

        repo.sync()

        assertEquals(1, events.getUnsynced().size, "an unknown outcome leaves the row for a newer client to retry")
    }

    @Test
    fun networkFailure_isFailure_andLeavesRowsUnsynced() = runUnitTest {
        val events = FakePlayerStatEventDao(row("k1"))
        val stats = FakePlayerStatsDao()
        val repo = build(stats = stats, events = events) {
            respond(ByteReadChannel("boom"), HttpStatusCode.InternalServerError)
        }

        val result = repo.sync()

        assertTrue(result.isFailure)
        assertEquals(1, events.getUnsynced().size, "a failed sync keeps rows pending")
        assertNull(stats.get(), "a failed sync doesn't cache a snapshot")
    }

    // --- helpers ---

    private fun handSummary(
        handId: String,
        mode: XpMode = XpMode.BOTS,
        won: Boolean = false,
        vsBot: Boolean = false,
        beatenBotId: String? = null,
        noBustStreak: Long = 0,
    ) = PlayerStatHandSummary(
        handId = handId,
        mode = mode,
        won = won,
        folded = false,
        lostAtShowdown = false,
        vsBot = vsBot,
        beatenBotId = beatenBotId,
        noBustStreak = noBustStreak,
    )

    private fun row(key: String) = PlayerStatEventEntity(
        idempotencyKey = key,
        synced = false,
        mode = "BOTS",
        won = true,
        folded = false,
        lostAtShowdown = false,
        vsBot = true,
        beatenBotId = "Jane",
        noBustStreak = 1,
        createdAtEpochMs = 1_000L,
    )

    private fun build(
        stats: FakePlayerStatsDao = FakePlayerStatsDao(),
        events: FakePlayerStatEventDao = FakePlayerStatEventDao(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): PlayerStatsRepositoryImpl {
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
        return PlayerStatsRepositoryImpl(
            playerStatsDao = stats,
            playerStatEventDao = events,
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

    private class FakePlayerStatsDao : PlayerStatsDao {
        private var entity: PlayerStatsEntity? = null
        private val flow = MutableStateFlow<PlayerStatsEntity?>(null)
        override fun observe(): Flow<PlayerStatsEntity?> = flow.asStateFlow()
        override suspend fun get(): PlayerStatsEntity? = entity
        override suspend fun set(entity: PlayerStatsEntity) {
            this.entity = entity
            flow.value = entity
        }
        override suspend fun deleteAll() {
            entity = null
            flow.value = null
        }
    }

    private class FakePlayerStatEventDao(vararg seed: PlayerStatEventEntity) : PlayerStatEventDao {
        private val rows = seed.toMutableList()
        override suspend fun insertAll(events: List<PlayerStatEventEntity>) { rows += events }
        override suspend fun getUnsynced(): List<PlayerStatEventEntity> = rows.filter { !it.synced }
        override suspend fun markSynced(keys: List<String>) {
            val set = keys.toSet()
            for (i in rows.indices) if (rows[i].idempotencyKey in set) rows[i] = rows[i].copy(synced = true)
        }
        override suspend fun deleteAll() = rows.clear()
    }
}
