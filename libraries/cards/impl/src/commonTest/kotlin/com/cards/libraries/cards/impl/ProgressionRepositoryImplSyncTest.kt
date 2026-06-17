package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.storage.db.ProgressionDao
import com.dangerfield.cards.libraries.cards.storage.db.ProgressionEntity
import com.dangerfield.cards.libraries.cards.storage.db.XpEventDao
import com.dangerfield.cards.libraries.cards.storage.db.XpEventEntity
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
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
import kotlinx.coroutines.job
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
 *  - onUserChanged(current != null) fires a sync; sign-out (current == null) does not.
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
    fun onUserChanged_toAUser_launchesSync() = runUnitTest {
        val xpDao = FakeXpEventDao()
        val appScope = AppCoroutineScope(dispatchers)
        var hits = 0
        val repo = buildRepoWithScope(FakeProgressionDao(seedTotalXp = 0L), xpDao, appScope) {
            hits++
            respondJson("""{"schemaVersion":1,"totalXp":500,"results":[]}""")
        }

        repo.onUserChanged(AppEvent.UserChanged(previous = "old", current = "new"))
        appScope.coroutineContext.job.children.toList().forEach { it.join() }

        assertEquals(1, hits, "an incoming user hydrates XP")
    }

    @Test
    fun onAccountClaimed_launchesSync() = runUnitTest {
        val xpDao = FakeXpEventDao()
        val appScope = AppCoroutineScope(dispatchers)
        var hits = 0
        val repo = buildRepoWithScope(FakeProgressionDao(seedTotalXp = 0L), xpDao, appScope) {
            hits++
            respondJson("""{"schemaVersion":1,"totalXp":500,"results":[]}""")
        }

        repo.onAccountClaimed(AppEvent.AccountClaimed(userId = "guest-1"))
        appScope.coroutineContext.job.children.toList().forEach { it.join() }

        assertEquals(1, hits, "a just-claimed account flushes XP without waiting for foreground")
    }

    @Test
    fun onUserChanged_toSignedOut_doesNotSync() = runUnitTest {
        val appScope = AppCoroutineScope(dispatchers)
        var hits = 0
        val repo = buildRepoWithScope(FakeProgressionDao(seedTotalXp = 0L), FakeXpEventDao(), appScope) {
            hits++
            respondJson("""{"schemaVersion":1,"totalXp":0,"results":[]}""")
        }

        repo.onUserChanged(AppEvent.UserChanged(previous = "old", current = null))

        assertEquals(0, hits, "sign-out has nothing to fetch")
        assertTrue(appScope.coroutineContext.job.children.toList().isEmpty())
    }

    // ---------- Scaffolding ----------

    private fun buildRepo(
        progressionDao: FakeProgressionDao,
        xpDao: FakeXpEventDao,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ProgressionRepositoryImpl =
        buildRepoWithScope(progressionDao, xpDao, AppCoroutineScope(dispatchers), handler)

    private fun buildRepoWithScope(
        progressionDao: FakeProgressionDao,
        xpDao: FakeXpEventDao,
        appScope: AppCoroutineScope,
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
            appScope = appScope,
            clock = FixedClock,
        )
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

        override suspend fun deleteAll() {
            rows.clear()
            flow.value = emptyList()
        }
    }
}
