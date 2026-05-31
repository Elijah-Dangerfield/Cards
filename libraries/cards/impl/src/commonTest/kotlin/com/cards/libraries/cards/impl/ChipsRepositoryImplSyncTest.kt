package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.storage.db.ChipsDao
import com.dangerfield.cards.libraries.cards.storage.db.ChipsEntity
import com.dangerfield.cards.libraries.cards.storage.db.WalletEventDao
import com.dangerfield.cards.libraries.cards.storage.db.WalletEventEntity
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pins [ChipsRepositoryImpl.sync] reconciliation logic via a Ktor MockEngine
 * stubbing `POST /v1/me/wallet/sync`.
 *
 * What's pinned:
 *  - Empty pending list still issues a sync — that's the foreground
 *    hydrate that picks up cross-device grants.
 *  - All-Applied response → events deleted from local; balance reset to
 *    server's authoritative value.
 *  - AlreadyApplied (replay) → events deleted; same balance reset.
 *  - InsufficientChips → events deleted (no retry possible); balance
 *    reset to authoritative.
 *  - Unknown outcome → row stays pending (so a newer client can resolve).
 *  - Network failure → returns Result.failure, leaves rows pending.
 */
class ChipsRepositoryImplSyncTest : CoroutineTest() {

    @Test
    fun emptyPending_stillIssuesSync_andResetsBalance() = runUnitTest {
        val chipsDao = FakeChipsDao(seedBalance = 7777L)
        val walletDao = FakeWalletEventDao()
        var hitCount = 0
        val repo = buildRepo(chipsDao, walletDao) {
            hitCount++
            respondJson("""{"schemaVersion":1,"balance":12345,"results":[]}""")
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertEquals(1, hitCount, "empty batch still triggers the sync (hydrate)")
        assertEquals(12_345L, chipsDao.getChips()?.balance)
        assertTrue(walletDao.getAll().isEmpty())
    }

    @Test
    fun sync_walletCreatedTrue_setsRequiresGrantInfo() = runUnitTest {
        val appCache = FakeAppCache()
        val repo = buildRepo(FakeChipsDao(seedBalance = 0L), FakeWalletEventDao(), appCache) {
            respondJson("""{"schemaVersion":1,"balance":10500,"results":[],"walletCreated":true}""")
        }

        repo.sync()

        assertTrue(appCache.get().requiresGrantInfo, "walletCreated=true must set requiresGrantInfo")
    }

    @Test
    fun sync_walletCreatedFalseOrAbsent_leavesFlagUntouched() = runUnitTest {
        val appCache = FakeAppCache()
        // No walletCreated field at all (older/returning-user response).
        val repo = buildRepo(FakeChipsDao(seedBalance = 0L), FakeWalletEventDao(), appCache) {
            respondJson("""{"schemaVersion":1,"balance":10000,"results":[]}""")
        }

        repo.sync()

        assertEquals(false, appCache.get().requiresGrantInfo)
    }

    @Test
    fun allApplied_deletesLocalEvents_andResetsBalanceToAuthoritative() = runUnitTest {
        val walletDao = FakeWalletEventDao().apply {
            insert(walletEvent("k1", delta = 250))
            insert(walletEvent("k2", delta = -100))
        }
        val chipsDao = FakeChipsDao(seedBalance = 0L)
        val repo = buildRepo(chipsDao, walletDao) {
            respondJson(
                """
                {
                  "schemaVersion":1,
                  "balance":11150,
                  "results":[
                    {"idempotencyKey":"k1","outcome":"Applied","balance":11250},
                    {"idempotencyKey":"k2","outcome":"Applied","balance":11150}
                  ]
                }
                """.trimIndent(),
            )
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertTrue(walletDao.getAll().isEmpty(), "both events resolved → all local rows deleted")
        assertEquals(11_150L, chipsDao.getChips()?.balance)
    }

    @Test
    fun alreadyApplied_isTreatedAsResolved() = runUnitTest {
        val walletDao = FakeWalletEventDao().apply {
            insert(walletEvent("retry_key", delta = 50))
        }
        val chipsDao = FakeChipsDao(seedBalance = 0L)
        val repo = buildRepo(chipsDao, walletDao) {
            respondJson(
                """
                {
                  "schemaVersion":1,
                  "balance":10000,
                  "results":[
                    {"idempotencyKey":"retry_key","outcome":"AlreadyApplied","balance":10000}
                  ]
                }
                """.trimIndent(),
            )
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertTrue(walletDao.getAll().isEmpty(), "replay-acknowledged events drop from local")
    }

    @Test
    fun insufficientChips_dropsRow_andResetsBalance() = runUnitTest {
        // The client optimistically debited but the server refused; the
        // local balance must converge back to the authoritative value
        // (which is what it was BEFORE the debit, since the server
        // didn't apply the event).
        val walletDao = FakeWalletEventDao().apply {
            insert(walletEvent("bad_debit", delta = -1_000_000))
        }
        val chipsDao = FakeChipsDao(seedBalance = 0L)
        val repo = buildRepo(chipsDao, walletDao) {
            respondJson(
                """
                {
                  "schemaVersion":1,
                  "balance":10000,
                  "results":[
                    {"idempotencyKey":"bad_debit","outcome":"InsufficientChips","balance":10000}
                  ]
                }
                """.trimIndent(),
            )
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertTrue(walletDao.getAll().isEmpty(), "rejected event drops — no retry pathway")
        assertEquals(10_000L, chipsDao.getChips()?.balance)
    }

    @Test
    fun unknownOutcome_leavesRowPending_forNewerClientToResolve() = runUnitTest {
        val walletDao = FakeWalletEventDao().apply {
            insert(walletEvent("mystery", delta = 100))
        }
        val chipsDao = FakeChipsDao(seedBalance = 0L)
        val repo = buildRepo(chipsDao, walletDao) {
            respondJson(
                """
                {
                  "schemaVersion":1,
                  "balance":10100,
                  "results":[
                    {"idempotencyKey":"mystery","outcome":"From_The_Future","balance":10100}
                  ]
                }
                """.trimIndent(),
            )
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("mystery"),
            walletDao.getAll().map { it.idempotencyKey },
            "unknown outcome → row stays so a newer client can handle it",
        )
        // The authoritative balance is still synced.
        assertEquals(10_100L, chipsDao.getChips()?.balance)
    }

    @Test
    fun transient5xxThenSuccess_succeedsAfterRetry() = runUnitTest {
        // wallet.sync runs under RetryPolicy.idempotent() — server's
        // (user_id, idempotency_key) PK makes the endpoint safe to replay,
        // so a transient 5xx should NOT bubble up to the user.
        val walletDao = FakeWalletEventDao().apply {
            insert(walletEvent("k1", delta = 50))
        }
        val chipsDao = FakeChipsDao(seedBalance = 4242L)
        var hitCount = 0
        val repo = buildRepo(chipsDao, walletDao) {
            hitCount++
            if (hitCount == 1) {
                respond(content = ByteReadChannel(""), status = HttpStatusCode.InternalServerError)
            } else {
                respondJson("""{"schemaVersion":1,"balance":4292,"results":[{"idempotencyKey":"k1","outcome":"Applied","balance":4292}]}""")
            }
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertEquals(2, hitCount, "1 transient 5xx + 1 successful retry")
        assertTrue(walletDao.getAll().isEmpty(), "applied event is purged on success")
        assertEquals(4292L, chipsDao.getChips()?.balance, "balance updated to server's value")
    }

    @Test
    fun networkFailure_returnsFailure_keepsRowsPending() = runUnitTest {
        val walletDao = FakeWalletEventDao().apply {
            insert(walletEvent("k1", delta = 50))
        }
        // Seed with a marker so we can detect whether setBalance ran.
        val chipsDao = FakeChipsDao(seedBalance = 4242L)
        val repo = buildRepo(chipsDao, walletDao) {
            respond(content = ByteReadChannel(""), status = HttpStatusCode.InternalServerError)
        }

        val result = repo.sync()

        assertTrue(result.isFailure)
        assertEquals(
            listOf("k1"),
            walletDao.getAll().map { it.idempotencyKey },
            "network failure → local row stays for next-launch retry",
        )
        assertEquals(
            4242L,
            chipsDao.getChips()?.balance,
            "no authoritative-balance update on failed sync",
        )
    }

    @Test
    fun mixedOutcomes_dropResolvedOnly() = runUnitTest {
        val walletDao = FakeWalletEventDao().apply {
            insert(walletEvent("k_applied", delta = 100))
            insert(walletEvent("k_already", delta = 50))
            insert(walletEvent("k_unknown", delta = 10))
            insert(walletEvent("k_rejected", delta = -1_000_000))
        }
        val chipsDao = FakeChipsDao(seedBalance = 0L)
        val repo = buildRepo(chipsDao, walletDao) {
            respondJson(
                """
                {
                  "schemaVersion":1,
                  "balance":10150,
                  "results":[
                    {"idempotencyKey":"k_applied","outcome":"Applied","balance":10100},
                    {"idempotencyKey":"k_already","outcome":"AlreadyApplied","balance":10100},
                    {"idempotencyKey":"k_unknown","outcome":"Mystery","balance":10100},
                    {"idempotencyKey":"k_rejected","outcome":"InsufficientChips","balance":10150}
                  ]
                }
                """.trimIndent(),
            )
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("k_unknown"),
            walletDao.getAll().map { it.idempotencyKey },
            "only the unknown event stays — everything else is resolved (incl. rejected)",
        )
    }

    @Test
    fun onColdBoot_launchesSync() = runUnitTest {
        val chipsDao = FakeChipsDao(seedBalance = 0L)
        val walletDao = FakeWalletEventDao()
        var hitCount = 0
        val appScope = AppCoroutineScope(dispatchers)
        val repo = buildRepoWithScope(chipsDao, walletDao, appScope) {
            hitCount++
            respondJson("""{"schemaVersion":1,"balance":555,"results":[]}""")
        }

        repo.onColdBoot(AppEvent.ColdBoot)
        appScope.coroutineContext.job.children.toList().forEach { it.join() }

        assertEquals(1, hitCount, "ColdBoot should fire one sync POST")
        assertEquals(555L, chipsDao.getChips()?.balance)
    }

    @Test
    fun onForeground_coldBoot_isNoOp_warmResume_syncs() = runUnitTest {
        val chipsDao = FakeChipsDao(seedBalance = 0L)
        val walletDao = FakeWalletEventDao()
        var hitCount = 0
        val appScope = AppCoroutineScope(dispatchers)
        val repo = buildRepoWithScope(chipsDao, walletDao, appScope) {
            hitCount++
            respondJson("""{"schemaVersion":1,"balance":42,"results":[]}""")
        }

        // ColdBoot-flagged foreground defers to onColdBoot — this hook
        // returns synchronously without launching anything, so there's
        // no scope work to join.
        repo.onForeground(AppEvent.OnForeground(isColdBoot = true))
        assertEquals(0, hitCount, "ColdBoot-flagged foreground must not POST")
        assertTrue(
            appScope.coroutineContext.job.children.toList().isEmpty(),
            "no coroutine should have been launched",
        )

        repo.onForeground(AppEvent.OnForeground(isColdBoot = false))
        appScope.coroutineContext.job.children.toList().forEach { it.join() }
        assertEquals(1, hitCount, "Warm-resume foreground should fire a sync POST")
    }

    @Test
    fun concurrentSync_serializesThroughMutex_noOverlappingPosts() = runUnitTest {
        // Two parallel sync() callers must not have overlapping POSTs in
        // flight — the syncMutex serializes them. Without the mutex, the
        // suspending delay in the handler would let the second caller's
        // begin land between the first's begin and end, producing the
        // interleaved log start-1, start-2, end-1, end-2.
        val chipsDao = FakeChipsDao(seedBalance = 0L)
        val walletDao = FakeWalletEventDao()
        val log = mutableListOf<String>()
        var seq = 0
        val repo = buildRepo(chipsDao, walletDao) {
            val n = ++seq
            log += "start-$n"
            delay(50)
            log += "end-$n"
            respondJson("""{"schemaVersion":1,"balance":0,"results":[]}""")
        }

        val results = listOf(
            async { repo.sync() },
            async { repo.sync() },
        ).awaitAll()

        assertTrue(results.all { it.isSuccess })
        assertEquals(2, seq, "both calls reach the handler")
        assertEquals(
            listOf("start-1", "end-1", "start-2", "end-2"),
            log,
            "second sync must wait for the first to finish — no overlapping POSTs",
        )
    }

    // ---------- Scaffolding ----------

    private fun buildRepo(
        chipsDao: FakeChipsDao,
        walletDao: FakeWalletEventDao,
        appCache: FakeAppCache = FakeAppCache(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ChipsRepositoryImpl = buildRepoWithScope(
        chipsDao = chipsDao,
        walletDao = walletDao,
        appScope = AppCoroutineScope(dispatchers),
        appCache = appCache,
        handler = handler,
    )

    private fun buildRepoWithScope(
        chipsDao: FakeChipsDao,
        walletDao: FakeWalletEventDao,
        appScope: AppCoroutineScope,
        appCache: FakeAppCache = FakeAppCache(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ChipsRepositoryImpl {
        val mockEngine = MockEngine(handler)
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        coerceInputValues = true
                    },
                )
            }
            // Match production: 4xx/5xx throws (ServerResponse/ClientRequestException).
            // Required so the retry policy's `is ResponseException` branch sees a 5xx
            // instead of a downstream JSON-parse failure on an empty error body.
            expectSuccess = true
        }
        @OptIn(com.dangerfield.cards.libraries.networking.InternalNetworkingApi::class)
        val networkClient = object : NetworkClient {
            override val client: HttpClient = client
            override val authenticatedClient: HttpClient = client
            override suspend fun awaitAuthReady() = Unit
        }
        return ChipsRepositoryImpl(
            chipsDao = chipsDao,
            walletEventDao = walletDao,
            networkClient = networkClient,
            appScope = appScope,
            clock = FixedClock,
            appCache = appCache,
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

    private fun walletEvent(
        key: String,
        delta: Long,
        reason: String = "test",
        appliedAtEpochMs: Long = 1_000,
    ) = WalletEventEntity(
        idempotencyKey = key,
        delta = delta,
        reason = reason,
        appliedAtEpochMs = appliedAtEpochMs,
    )

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
    }

    private class FakeAppCache(initial: AppData = AppData()) : AppCache {
        private val state = MutableStateFlow(initial)
        override val updates: Flow<AppData> = state
        override suspend fun get(): AppData = state.value
        override suspend fun set(value: AppData) { state.value = value }
        override suspend fun clear() { state.value = AppData() }
    }

    private class FakeWalletEventDao : WalletEventDao {
        private val rows = mutableListOf<WalletEventEntity>()
        override suspend fun getAll(): List<WalletEventEntity> =
            rows.sortedBy { it.appliedAtEpochMs }

        override suspend fun insert(entity: WalletEventEntity) {
            if (rows.none { it.idempotencyKey == entity.idempotencyKey }) {
                rows += entity
            }
        }

        override suspend fun deleteByKeys(keys: List<String>) {
            rows.removeAll { it.idempotencyKey in keys }
        }

        override suspend fun deleteAll() {
            rows.clear()
        }
    }

    private class FakeChipsDao(seedBalance: Long) : ChipsDao {
        private val state = MutableStateFlow<ChipsEntity?>(
            ChipsEntity(balance = seedBalance, updatedAtEpochMs = 0L),
        )

        override fun observeChips(): Flow<ChipsEntity?> = state.asStateFlow()
        override suspend fun getChips(): ChipsEntity? = state.value

        override suspend fun insertIfMissing(entity: ChipsEntity) {
            if (state.value == null) state.value = entity
        }

        override suspend fun applyDelta(delta: Long, updatedAtEpochMs: Long) {
            val current = state.value ?: return
            state.value = current.copy(
                balance = current.balance + delta,
                updatedAtEpochMs = updatedAtEpochMs,
            )
        }

        override suspend fun deleteAll() {
            state.value = null
        }
    }
}
