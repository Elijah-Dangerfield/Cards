package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.storage.db.ProgressionDao
import com.dangerfield.cards.libraries.cards.storage.db.ProgressionEntity
import com.dangerfield.cards.libraries.cards.storage.db.XpEventDao
import com.dangerfield.cards.libraries.cards.storage.db.XpEventEntity
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pins [ProgressionRepositoryImpl.sync] reconciliation via a Ktor MockEngine
 * stubbing `POST /v1/me/progression/sync`. Mirrors [ChipsRepositoryImplSyncTest].
 *
 * What's pinned:
 *  - Empty pending list still syncs (the hydrate that picks up cross-device XP).
 *  - Applied / AlreadyApplied → local rows marked synced; total reconciled to
 *    the server's authoritative value.
 *  - Unknown outcome → row stays unsynced (a newer client retries).
 *  - Network failure → Result.failure, rows stay unsynced, total untouched.
 *
 * The event-trigger gating (which edges fire a sync) lives in
 * [UserScopedSyncCoordinatorTest] now, not here.
 */
class ProgressionRepositoryImplSyncTest : CoroutineTest() {

    @Test
    fun emptyPending_stillSyncs_andReconcilesTotal() = runUnitTest {
        val progressionDao = FakeProgressionDao(seedTotalXp = 50L)
        val xpDao = FakeXpEventDao()
        var hits = 0
        val repo = buildRepo(progressionDao, xpDao) {
            hits++
            respondJson("""{"schemaVersion":1,"totalXp":1234,"results":[]}""")
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertEquals(1, hits, "empty batch still triggers the hydrate sync")
        assertEquals(1234L, progressionDao.getProgression()?.totalXp)
    }

    @Test
    fun appliedEvents_markedSynced_andTotalReconciled() = runUnitTest {
        val progressionDao = FakeProgressionDao(seedTotalXp = 0L)
        val xpDao = FakeXpEventDao(xpEvent("k1", 10), xpEvent("k2", 25))
        val repo = buildRepo(progressionDao, xpDao) {
            respondJson(
                """
                {"schemaVersion":1,"totalXp":35,"results":[
                  {"idempotencyKey":"k1","outcome":"Applied","totalXp":10},
                  {"idempotencyKey":"k2","outcome":"Applied","totalXp":35}
                ]}
                """.trimIndent(),
            )
        }

        repo.sync()

        assertTrue(xpDao.getUnsynced().isEmpty(), "applied rows are marked synced")
        assertEquals(35L, progressionDao.getProgression()?.totalXp)
    }

    @Test
    fun alreadyApplied_isTreatedAsSynced() = runUnitTest {
        val xpDao = FakeXpEventDao(xpEvent("k1", 10))
        val repo = buildRepo(FakeProgressionDao(seedTotalXp = 10L), xpDao) {
            respondJson(
                """{"schemaVersion":1,"totalXp":10,"results":[{"idempotencyKey":"k1","outcome":"AlreadyApplied","totalXp":10}]}""",
            )
        }

        repo.sync()

        assertTrue(xpDao.getUnsynced().isEmpty(), "replay (AlreadyApplied) still marks the row synced")
    }

    @Test
    fun unknownOutcome_leavesRowPending() = runUnitTest {
        val xpDao = FakeXpEventDao(xpEvent("k1", 10))
        val repo = buildRepo(FakeProgressionDao(seedTotalXp = 0L), xpDao) {
            respondJson(
                """{"schemaVersion":1,"totalXp":10,"results":[{"idempotencyKey":"k1","outcome":"Mystery","totalXp":10}]}""",
            )
        }

        repo.sync()

        assertEquals(
            listOf("k1"),
            xpDao.getUnsynced().map { it.idempotencyKey },
            "an unknown outcome leaves the row pending for a newer client",
        )
    }

    @Test
    fun networkFailure_returnsFailure_andLeavesRowsPending() = runUnitTest {
        val progressionDao = FakeProgressionDao(seedTotalXp = 99L)
        val xpDao = FakeXpEventDao(xpEvent("k1", 10))
        val repo = buildRepo(progressionDao, xpDao) {
            respondJson("""{"error":"boom"}""", status = HttpStatusCode.InternalServerError)
        }

        val result = repo.sync()

        assertTrue(result.isFailure)
        assertEquals(listOf("k1"), xpDao.getUnsynced().map { it.idempotencyKey }, "failed sync keeps rows pending")
        assertEquals(99L, progressionDao.getProgression()?.totalXp, "failed sync leaves the local total untouched")
    }

    @Test
    fun recentEvents_inResponse_rehydrateLocalLedgerAsSynced() = runUnitTest {
        // The reinstall / account-switch case: the local ledger is empty, so
        // the server's echoed recent rows seed it, marked synced.
        val xpDao = FakeXpEventDao()
        val repo = buildRepo(FakeProgressionDao(seedTotalXp = 0L), xpDao) {
            respondJson(
                """
                {"schemaVersion":1,"totalXp":2040,"results":[],"recentEvents":[
                  {"idempotencyKey":"srv1","deltaXp":40,"source":"hand","mode":"MULTIPLAYER","handId":"h1","appliedAtEpochMs":1000},
                  {"idempotencyKey":"srv2","deltaXp":2000,"source":"achievement","mode":"BOTS","appliedAtEpochMs":500}
                ]}
                """.trimIndent(),
            )
        }

        repo.sync()

        val keys = xpDao.observeRecentSnapshot().map { it.idempotencyKey }
        assertEquals(setOf("srv1", "srv2"), keys.toSet(), "echoed rows are inserted into the local feed")
        assertTrue(
            xpDao.getUnsynced().isEmpty(),
            "re-hydrated rows are already on the server → inserted as synced",
        )
    }

    @Test
    fun recentEvents_skipKeysAlreadyHeldLocally() = runUnitTest {
        // A row the client already holds must not be duplicated (no unique
        // index on the key — a naive insert would double the feed entry).
        val xpDao = FakeXpEventDao(xpEvent("srv1", 40).copy(synced = true))
        val repo = buildRepo(FakeProgressionDao(seedTotalXp = 40L), xpDao) {
            respondJson(
                """
                {"schemaVersion":1,"totalXp":2040,"results":[],"recentEvents":[
                  {"idempotencyKey":"srv1","deltaXp":40,"source":"hand","mode":"BOTS","handId":"hand-srv1","appliedAtEpochMs":1000},
                  {"idempotencyKey":"srv2","deltaXp":2000,"source":"achievement","mode":"BOTS","appliedAtEpochMs":500}
                ]}
                """.trimIndent(),
            )
        }

        repo.sync()

        val keys = xpDao.observeRecentSnapshot().map { it.idempotencyKey }
        assertEquals(listOf("srv1", "srv2"), keys, "the already-held key is not re-inserted")
    }

    @Test
    fun degradedPlayDeltas_replayOnceOnSync_andDoNotDoubleApply() = runUnitTest {
        // AUTH-2: while account creation is pending the user plays bots and
        // accrues XP locally (pending rows, no auth to flush them). The first
        // sync flushes those rows exactly once and reconciles the local total to
        // the server's authoritative value. A second sync replays the same keys,
        // the server reports AlreadyApplied, and the total must not double-count.
        val progressionDao = FakeProgressionDao(seedTotalXp = 70L)
        val xpDao = FakeXpEventDao(xpEvent("local-1", 30), xpEvent("local-2", 40))
        var hits = 0
        val repo = buildRepo(progressionDao, xpDao) {
            hits++
            when (hits) {
                1 -> respondJson(
                    """
                    {"schemaVersion":1,"totalXp":70,"results":[
                      {"idempotencyKey":"local-1","outcome":"Applied","totalXp":30},
                      {"idempotencyKey":"local-2","outcome":"Applied","totalXp":70}
                    ]}
                    """.trimIndent(),
                )
                else -> respondJson(
                    // The first sync marked both rows synced, so the second
                    // claim posts an empty batch and just re-hydrates the
                    // authoritative total — the server holds the deltas once.
                    """{"schemaVersion":1,"totalXp":70,"results":[]}""",
                )
            }
        }

        repo.sync()

        assertEquals(1, hits, "the first sync flushes the pending degraded-play deltas")
        assertTrue(xpDao.getUnsynced().isEmpty(), "applied degraded-play rows are marked synced")
        assertEquals(70L, progressionDao.getProgression()?.totalXp, "total reconciles to the server value")

        repo.sync()

        assertEquals(2, hits, "a second sync re-posts (now an empty/replayed batch)")
        assertEquals(70L, progressionDao.getProgression()?.totalXp, "replay does not double-apply the deltas")
    }

    @Test
    fun serverMintedLevelChips_triggerAWalletRePull() = runUnitTest {
        // PROG-12: level chips are minted server-side ON the progression sync
        // (ENG-9) — the wallet sync refuses the client's own levelup.* credit.
        // Without a re-pull here the reward stays invisible until the next
        // sync trigger (observed as "earned 1000 chips, stale until
        // force-kill"). A walletBalance in the response is the mint signal.
        val chips = RecordingChipsRepository()
        val repo = buildRepo(FakeProgressionDao(seedTotalXp = 0L), FakeXpEventDao(xpEvent("k1", 500)), chips) {
            respondJson(
                """
                {"schemaVersion":1,"totalXp":500,"results":[
                  {"idempotencyKey":"k1","outcome":"Applied","totalXp":500}
                ],"walletBalance":11000}
                """.trimIndent(),
            )
        }

        repo.sync()

        assertEquals(1, chips.syncCalls, "a server-side mint must trigger a wallet re-pull")
    }

    @Test
    fun noMint_doesNotTouchTheWallet() = runUnitTest {
        val chips = RecordingChipsRepository()
        val repo = buildRepo(FakeProgressionDao(seedTotalXp = 0L), FakeXpEventDao(), chips) {
            respondJson("""{"schemaVersion":1,"totalXp":42,"results":[]}""")
        }

        repo.sync()

        assertEquals(0, chips.syncCalls, "a mint-less sync must not spam wallet pulls")
    }

    // ---------- Scaffolding ----------

    private fun buildRepo(
        progressionDao: FakeProgressionDao,
        xpDao: FakeXpEventDao,
        chips: RecordingChipsRepository = RecordingChipsRepository(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ProgressionRepositoryImpl {
        val httpClient = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; coerceInputValues = true })
            }
            expectSuccess = true
        }
        @OptIn(com.dangerfield.cards.libraries.networking.InternalNetworkingApi::class)
        val networkClient = object : NetworkClient {
            override val client: HttpClient = httpClient
            override val authenticatedClient: HttpClient = httpClient
            override suspend fun awaitAuthReady() = Unit
        }
        return ProgressionRepositoryImpl(
            progressionDao = progressionDao,
            xpEventDao = xpDao,
            networkClient = networkClient,
            clock = FixedClock,
            xpBoostRepository = InactiveXpBoostRepository,
            chipsRepository = chips,
        )
    }

    private class RecordingChipsRepository : com.dangerfield.cards.libraries.cards.ChipsRepository {
        var syncCalls = 0
            private set
        override fun observeBalance(): Flow<Long?> = MutableStateFlow(null)
        override suspend fun getBalance(): Long? = null
        override suspend fun addChips(amount: Long, reason: String, idempotencyKey: String?) =
            error("unused in progression sync tests")
        override suspend fun subtractChips(amount: Long, reason: String, idempotencyKey: String?) =
            error("unused in progression sync tests")
        override suspend fun setBalance(authoritativeBalance: Long) =
            error("unused — the mint signal re-pulls, it never writes the snapshot directly")
        override suspend fun deleteAll() = error("unused in progression sync tests")
        override suspend fun sync(): Result<Unit> {
            syncCalls++
            return Result.success(Unit)
        }
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
    )

    private fun xpEvent(key: String, delta: Int) = XpEventEntity(
        idempotencyKey = key,
        synced = false,
        deltaXp = delta,
        source = "BASE",
        mode = "BOTS",
        handId = "hand-$key",
        createdAtEpochMs = 1_000L,
    )

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
    }

    private class FakeProgressionDao(seedTotalXp: Long?) : ProgressionDao {
        private var entity: ProgressionEntity? =
            seedTotalXp?.let { ProgressionEntity(totalXp = it) }
        private val flow = MutableStateFlow(entity)

        override fun observeProgression(): Flow<ProgressionEntity?> = flow.asStateFlow()
        override suspend fun getProgression(): ProgressionEntity? = entity

        override suspend fun insertIfMissing(entity: ProgressionEntity) {
            if (this.entity == null) {
                this.entity = entity
                flow.value = entity
            }
        }

        override suspend fun applyHandDeltas(
            xpDelta: Int,
            handsWonDelta: Int,
            handsFoldedDelta: Int,
            handsLostAtShowdownDelta: Int,
            botHandsPlayedDelta: Int,
            updatedAtEpochMs: Long,
        ) = error("unused in sync tests")

        override suspend fun addXpOnly(xpDelta: Int, updatedAtEpochMs: Long) =
            error("unused in sync tests")

        override suspend fun setTotalXp(totalXp: Long, updatedAtEpochMs: Long) {
            val current = entity ?: error("setTotalXp without ensureExists")
            entity = current.copy(totalXp = totalXp, updatedAtEpochMs = updatedAtEpochMs)
            flow.value = entity
        }

        override suspend fun deleteAll() {
            entity = null
            flow.value = null
        }
    }

    private class FakeXpEventDao(vararg seed: XpEventEntity) : XpEventDao {
        private val rows = seed.toMutableList()
        private val flow = MutableStateFlow(rows.toList())

        fun observeRecentSnapshot(): List<XpEventEntity> = rows.toList()

        override suspend fun insertAll(events: List<XpEventEntity>) {
            rows += events
            flow.value = rows.toList()
        }

        override fun observeSince(sinceEpochMs: Long): Flow<List<XpEventEntity>> = flow.asStateFlow()
        override fun observeRecent(limit: Int): Flow<List<XpEventEntity>> = flow.asStateFlow()

        override suspend fun getUnsynced(): List<XpEventEntity> = rows.filter { !it.synced }

        override suspend fun markSynced(keys: List<String>) {
            val set = keys.toSet()
            for (i in rows.indices) {
                if (rows[i].idempotencyKey in set) rows[i] = rows[i].copy(synced = true)
            }
            flow.value = rows.toList()
        }

        override suspend fun existingKeys(keys: List<String>): List<String> {
            val set = keys.toSet()
            return rows.map { it.idempotencyKey }.filter { it in set }
        }

        override suspend fun deleteAll() {
            rows.clear()
            flow.value = emptyList()
        }
    }
}
