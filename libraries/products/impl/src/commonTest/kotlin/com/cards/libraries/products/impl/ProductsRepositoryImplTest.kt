package com.dangerfield.cards.libraries.products.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.billing.BillingAvailability
import com.dangerfield.cards.libraries.billing.BillingProduct
import com.dangerfield.cards.libraries.billing.ConnectionState
import com.dangerfield.cards.libraries.billing.QueryProductsResult
import com.dangerfield.cards.libraries.cards.Session
import com.dangerfield.cards.libraries.cards.SessionStartReason
import com.dangerfield.cards.libraries.cards.SessionTracker
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.StoreSku
import com.dangerfield.cards.libraries.storage.Cache
import com.dangerfield.cards.libraries.storage.CacheFactory
import com.dangerfield.cards.libraries.storage.CacheJsonSerializer
import io.ktor.client.HttpClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Repository unit tests. Drive [ProductsRepositoryImpl] via fakes for
 * the data source, billing surface, session tracker, clock, and
 * persistent cache so we don't fight Ktor's mock engine or a real
 * file system for what amounts to cache-and-flow contracts.
 *
 * What we pin:
 *  - Subscribers see the empty catalog before first refresh (fresh
 *    install — no disk snapshot).
 *  - A successful refresh updates the flow.
 *  - A failed refresh leaves the prior successful catalog intact
 *    (cache isn't poisoned).
 *  - Concurrent refreshes coalesce — the data source is hit once.
 *  - Same-session non-forced refresh short-circuits.
 *  - Session rollover crosses the gate and re-fetches.
 *  - `force = true` always bypasses.
 *  - Disk hydration: the persisted catalog seeds the flow at init.
 *  - Stale disk snapshot (> 7 days) is dropped on init.
 *  - Store reconciliation: SKUs the store doesn't recognize are
 *    dropped; SKUs the store knows about have their fallback price
 *    overwritten.
 *  - Reconciliation failures don't poison the cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductsRepositoryImplTest : CoroutineTest() {

    @Test
    fun initialCatalog_isEmpty_whenNoDiskSnapshot() = runUnitTest {
        val repo = newRepo()
        repo.observeCatalog().test {
            assertEquals(ProductCatalog.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refresh_success_updatesObservedCatalog() = runUnitTest {
        // Note: the session-observer auto-refresh runs eagerly in init
        // under the UnconfinedTestDispatcher, so by the time the test
        // body starts the StateFlow already holds the post-refresh
        // catalog. The contract this pins: "after a successful fetch
        // (whether from init or explicit), the flow emits the
        // populated catalog."
        val source = FakeDataSource(catalog = SAMPLE_DTO)
        val repo = newRepo(source = source)
        runCurrent()

        val catalog = repo.observeCatalog().firstValue()
        assertEquals(1, catalog.chipPacks.size)
        assertEquals("Pocket Stack", catalog.chipPacks.first().title)
        assertEquals(5_000L, catalog.chipPacks.first().grantsChips)
        assertEquals(1, source.callCount, "init's session-observer should have done one fetch")
    }

    @Test
    fun refresh_failure_returnsFailureResult_andDoesNotPoisonCache() = runUnitTest {
        val source = FakeDataSource(catalog = SAMPLE_DTO)
        val repo = newRepo(source = source)
        // Prime with a successful refresh, then make the next call fail.
        // Force the second call so we don't short-circuit on the session
        // gate — we're verifying error handling, not cache semantics.
        repo.refresh()
        val baseline = repo.observeCatalog().firstValue()
        assertEquals(1, baseline.chipPacks.size)

        source.failNext = RuntimeException("simulated server 500")
        val result = repo.refresh(force = true)
        assertTrue(result.isFailure)
        assertEquals("simulated server 500", result.exceptionOrNull()?.message)

        // Cache is untouched — flow still emits the prior good catalog.
        assertEquals(baseline, repo.observeCatalog().firstValue())
    }

    @Test
    fun refreshFailed_raisedOnFailure_clearedByNextAttemptAndSuccess() = runUnitTest {
        // SHOP-10: consumers key their error/retry surface off this flag, so
        // it must move for repo-driven refreshes too — raised when an attempt
        // fails, cleared the moment the next attempt starts / succeeds.
        val source = FakeDataSource(catalog = SAMPLE_DTO)
        val repo = newRepo(source = source)
        runCurrent() // init auto-refresh succeeds
        assertFalse(repo.observeRefreshFailed().firstValue())

        source.failNext = RuntimeException("server 500")
        assertTrue(repo.refresh(force = true).isFailure)
        assertTrue(repo.observeRefreshFailed().firstValue(), "a failed attempt raises the flag")

        assertTrue(repo.refresh(force = true).isSuccess)
        assertFalse(repo.observeRefreshFailed().firstValue(), "the next success clears it")
    }

    @Test
    fun refresh_isResultBased_notExceptionEscaping() = runUnitTest {
        val source = FakeDataSource(catalog = SAMPLE_DTO).apply {
            failNext = RuntimeException("boom")
        }
        val repo = newRepo(source = source)
        try {
            repo.refresh()
        } catch (e: Throwable) {
            fail("refresh should NOT throw; should wrap in Result: $e")
        }
    }

    @Test
    fun concurrentRefreshes_coalesce_toSingleDataSourceCall() = runUnitTest {
        val source = FakeDataSource(catalog = SAMPLE_DTO)
        val repo = newRepo(source = source)
        // 4 refreshes "concurrently". UnconfinedTestDispatcher means they
        // serialize through the same mutex; once one succeeds the session
        // gate short-circuits the remaining three. Net: exactly one
        // network call for the burst.
        val results = listOf(
            async { repo.refresh() },
            async { repo.refresh() },
            async { repo.refresh() },
            async { repo.refresh() },
        ).awaitAll()
        assertTrue(results.all { it.isSuccess })
        assertEquals(1, source.callCount, "session gate must fold the burst into one fetch")
        assertEquals(1, repo.observeCatalog().firstValue().chipPacks.size)
    }

    // ---------- Session-aware refresh gate ----------

    @Test
    fun refresh_withinSameSession_doesNotHitNetwork() = runUnitTest {
        val source = FakeDataSource(catalog = SAMPLE_DTO)
        val repo = newRepo(source = source)

        assertTrue(repo.refresh().isSuccess)
        val callsAfterFirst = source.callCount
        assertEquals(1, callsAfterFirst)

        // Second call inside the same session — short-circuit.
        assertTrue(repo.refresh().isSuccess)
        assertEquals(
            callsAfterFirst, source.callCount,
            "non-forced refresh within the same session must not hit the data source",
        )
    }

    @Test
    fun refresh_afterSessionRollover_hitsNetwork() = runUnitTest {
        val source = FakeDataSource(catalog = SAMPLE_DTO)
        val sessions = FakeSessionTracker(initial = coldBootSession(id = 1L))
        val repo = newRepo(source = source, sessions = sessions)

        assertTrue(repo.refresh().isSuccess)
        assertEquals(1, source.callCount)

        // App backgrounds, comes back > 15 min later: session #2.
        sessions.roll(toId = 2L)
        runCurrent() // let the session observer's auto-refresh land
        assertEquals(2, source.callCount, "new session must trigger a fresh fetch")
    }

    @Test
    fun refresh_forceTrue_bypassesSessionGate() = runUnitTest {
        val source = FakeDataSource(catalog = SAMPLE_DTO)
        val repo = newRepo(source = source)

        assertTrue(repo.refresh().isSuccess)
        val callsAfterFirst = source.callCount

        // Pull-to-refresh path — must always hit the data source.
        assertTrue(repo.refresh(force = true).isSuccess)
        assertEquals(
            callsAfterFirst + 1, source.callCount,
            "force = true must bypass the session gate",
        )
    }

    @Test
    fun refresh_failureKeepsSessionGateOpen_soNextNonForcedRefreshRetries() = runUnitTest {
        // Failed refresh must NOT mark the session as fetched —
        // otherwise the user could be stuck with empty state for the
        // whole session if their cold-boot fetch happened to fail.
        // We let init succeed, then force a rollover where the next
        // fetch fails, then verify a follow-up non-forced refresh
        // still hits the network for that same (post-rollover) session.
        val source = FakeDataSource(catalog = SAMPLE_DTO)
        val sessions = FakeSessionTracker(initial = coldBootSession(id = 1L))
        val repo = newRepo(source = source, sessions = sessions)
        runCurrent()
        val callsAfterInit = source.callCount
        assertEquals(1, callsAfterInit, "init auto-refresh should land")

        source.failNext = RuntimeException("rollover fetch fails")
        sessions.roll(toId = 2L)
        runCurrent()
        assertEquals(callsAfterInit + 1, source.callCount, "rollover triggered a fetch")

        // The failed rollover fetch did not mark session 2 as fetched,
        // so a follow-up non-forced refresh inside session 2 must hit
        // the network again.
        assertTrue(repo.refresh().isSuccess)
        assertEquals(callsAfterInit + 2, source.callCount)
    }

    // ---------- Disk persistence ----------

    @Test
    fun init_hydratesPersistedCatalog_intoObservedFlow() = runUnitTest {
        // Seed with the SAME session id as the tracker's current
        // session — that way the session observer doesn't trigger an
        // auto-refresh that would clobber the hydrated state. We're
        // pinning hydration here, not the rollover-refresh path.
        val cacheFactory = RecordingCacheFactory()
        cacheFactory.seedProductsCache(
            catalog = SAMPLE_DOMAIN,
            lastFetchSessionId = 1L,
            fetchedAtEpochMs = NOW_MS - 2.hours.inWholeMilliseconds,
            serverNowEpochMs = NOW_MS - 2.hours.inWholeMilliseconds,
        )

        val repo = newRepo(cacheFactory = cacheFactory)
        runCurrent() // let init's appScope.launch run

        // First emission is the hydrated catalog — UI never sees Empty
        // on warm starts when the disk has a snapshot.
        assertEquals(SAMPLE_DOMAIN, repo.observeCatalog().firstValue())
    }

    @Test
    fun init_dropsSnapshotOlderThanMaxAge() = runUnitTest {
        val cacheFactory = RecordingCacheFactory()
        // 8-day-old snapshot — past the 7-day max age.
        cacheFactory.seedProductsCache(
            catalog = SAMPLE_DOMAIN,
            lastFetchSessionId = 1L,
            fetchedAtEpochMs = NOW_MS - 8.days.inWholeMilliseconds,
            serverNowEpochMs = NOW_MS - 8.days.inWholeMilliseconds,
        )
        val source = FakeDataSource(catalog = SAMPLE_DTO)

        val repo = newRepo(source = source, cacheFactory = cacheFactory)
        runCurrent()

        // Stale snapshot dropped; the session observer's auto-refresh
        // picks up fresh data instead.
        assertEquals(1, source.callCount, "session observer should refresh after stale-drop")
    }

    @Test
    fun refresh_success_writesSnapshotToDisk() = runUnitTest {
        val cacheFactory = RecordingCacheFactory()
        val source = FakeDataSource(catalog = SAMPLE_DTO)
        val repo = newRepo(source = source, cacheFactory = cacheFactory)
        runCurrent() // hydrate

        repo.refresh()
        runCurrent() // let the appScope.launch persist

        val persisted = cacheFactory.productsCache.read()
        assertEquals(SAMPLE_DOMAIN, persisted.catalog)
        assertEquals(NOW_MS, persisted.fetchedAtEpochMs)
        assertNotEquals(null, persisted.lastFetchSessionId)
    }

    @Test
    fun init_withCachedSessionId_matchingCurrent_doesNotAutoRefresh() = runUnitTest {
        // Same session as hydrated — no auto-refresh.
        val cacheFactory = RecordingCacheFactory()
        cacheFactory.seedProductsCache(
            catalog = SAMPLE_DOMAIN,
            lastFetchSessionId = 1L,
            fetchedAtEpochMs = NOW_MS - 1.hours.inWholeMilliseconds,
            serverNowEpochMs = NOW_MS - 1.hours.inWholeMilliseconds,
        )
        val source = FakeDataSource(catalog = SAMPLE_DTO)
        val sessions = FakeSessionTracker(initial = coldBootSession(id = 1L))

        newRepo(source = source, sessions = sessions, cacheFactory = cacheFactory)
        runCurrent()

        assertEquals(
            0, source.callCount,
            "session id matches the hydrated snapshot — should not auto-refresh",
        )
    }

    // ---------- Reconciliation ----------

    @Test
    fun reconcile_dropsPacks_whoseSku_storeDoesNotRecognize() = runUnitTest {
        val dto = ProductCatalogDto(
            chipPacks = listOf(
                ChipPackDto(
                    id = "small",
                    title = "Pocket Stack",
                    subtitle = "5,000 chips",
                    iconEmoji = "🪙",
                    grantsChips = 5_000,
                    store = StoreSkuDto("chips_small", "$0.99"),
                ),
                ChipPackDto(
                    id = "large",
                    title = "Whale Stack",
                    subtitle = "80,000 chips",
                    iconEmoji = "🐋",
                    grantsChips = 80_000,
                    store = StoreSkuDto("chips_large", "$9.99"),
                ),
            ),
        )
        val billing = FakeBillingAvailability(
            recognizedSkus = mapOf(
                "chips_small" to BillingProduct("chips_small", "$0.99", "USD", 990_000),
            ),
        )
        val repo = newRepo(source = FakeDataSource(dto), billing = billing)

        repo.refresh()

        val catalog = repo.observeCatalog().firstValue()
        assertEquals(1, catalog.chipPacks.size)
        assertEquals("small", catalog.chipPacks.first().id)
    }

    @Test
    fun reconcile_overlaysLocalizedPrice_onMatchingSku() = runUnitTest {
        val dto = ProductCatalogDto(
            chipPacks = listOf(
                ChipPackDto(
                    id = "small",
                    title = "Pocket Stack",
                    subtitle = "5,000 chips",
                    iconEmoji = "🪙",
                    grantsChips = 5_000,
                    store = StoreSkuDto("chips_small", "$0.99"),
                ),
            ),
        )
        val billing = FakeBillingAvailability(
            recognizedSkus = mapOf(
                "chips_small" to BillingProduct("chips_small", "€0,89", "EUR", 890_000),
            ),
        )
        val repo = newRepo(source = FakeDataSource(dto), billing = billing)

        repo.refresh()

        val catalog = repo.observeCatalog().firstValue()
        assertEquals(1, catalog.chipPacks.size)
        assertEquals("€0,89", catalog.chipPacks.first().store.fallbackPriceDisplay)
    }

    @Test
    fun reconcile_emptyStore_dropsAllIapPacks() = runUnitTest {
        val dto = ProductCatalogDto(
            chipPacks = listOf(
                ChipPackDto(
                    id = "small",
                    title = "Pocket Stack",
                    subtitle = "5,000 chips",
                    iconEmoji = "🪙",
                    grantsChips = 5_000,
                    store = StoreSkuDto("chips_small", "$0.99"),
                ),
            ),
            chipOffers = listOf(
                ChipOfferDto(
                    id = "felt_red",
                    title = "Royal Red Felt",
                    subtitle = "Table felt",
                    iconEmoji = "🟥",
                    costChips = 1_500,
                    grantsKey = "felt.royal_red",
                ),
            ),
        )
        val billing = FakeBillingAvailability(recognizedSkus = emptyMap())
        val repo = newRepo(source = FakeDataSource(dto), billing = billing)

        repo.refresh()

        val catalog = repo.observeCatalog().firstValue()
        assertTrue(catalog.chipPacks.isEmpty(), "IAP packs should be dropped when store knows none")
        assertEquals(1, catalog.chipOffers.size, "Chip offers should pass through")
    }

    @Test
    fun reconcile_storeNotConnected_fallsBackToCachedSnapshot() = runUnitTest {
        val firstStoreState = FakeBillingAvailability(
            recognizedSkus = mapOf("chips_small" to BillingProduct("chips_small", "$0.99", "USD", 990_000)),
        )
        val repo = newRepo(source = FakeDataSource(SAMPLE_DTO), billing = firstStoreState)
        repo.refresh()
        assertEquals(1, repo.observeCatalog().firstValue().chipPacks.size)

        // Second refresh: store reports NotConnected. With the snapshot
        // still populated, the pack should remain. Force so the session
        // gate doesn't short-circuit before reconciliation runs.
        firstStoreState.nextResult = QueryProductsResult.NotConnected
        repo.refresh(force = true)
        val after = repo.observeCatalog().firstValue()
        assertEquals(1, after.chipPacks.size)
        assertFalse(after.chipPacks.isEmpty())
    }

    // ---------- Test scaffolding ----------

    private fun newRepo(
        source: FakeDataSource = FakeDataSource(),
        billing: FakeBillingAvailability = FakeBillingAvailability.passthrough(),
        sessions: FakeSessionTracker = FakeSessionTracker(initial = coldBootSession(id = 1L)),
        cacheFactory: RecordingCacheFactory = RecordingCacheFactory(),
        clock: Clock = FixedClock(NOW_MS),
    ): ProductsRepositoryImpl = ProductsRepositoryImpl(
        dataSource = source,
        billingAvailability = billing,
        sessionTracker = sessions,
        clock = clock,
        appScope = AppCoroutineScope(dispatchers),
        cacheFactory = cacheFactory,
    )

    private fun coldBootSession(id: Long): Session = Session(
        id = id,
        startedAtMs = NOW_MS,
        reason = SessionStartReason.ColdBoot,
        uuid = "session-$id",
    )

    private class FakeDataSource(
        var catalog: ProductCatalogDto = ProductCatalogDto(),
    ) : ProductsHttpDataSource(networkClient = StubNetworkClient) {
        var failNext: Throwable? = null
        var callCount: Int = 0
            private set

        override suspend fun fetchCatalog(): Result<ProductCatalogDto> {
            callCount++
            failNext?.let { failNext = null; return Result.failure(it) }
            return Result.success(catalog)
        }
    }

    /**
     * Test double for [BillingAvailability]. Returns [recognizedSkus]
     * for any refresh by default; override per-call via [nextResult] to
     * exercise the NotConnected / Failed branches. Set [passthrough] to
     * auto-recognize any SKU queried (preserving the catalog as-is for
     * tests that don't care about reconciliation).
     */
    private class FakeBillingAvailability(
        var recognizedSkus: Map<String, BillingProduct> = emptyMap(),
        private val passthrough: Boolean = false,
    ) : BillingAvailability {
        var nextResult: QueryProductsResult? = null

        private val _products = MutableStateFlow(recognizedSkus)
        override val products: Flow<Map<String, BillingProduct>> = _products
        override val connectionState: Flow<ConnectionState> = MutableStateFlow(ConnectionState.Connected)

        override suspend fun refresh(skus: Set<String>): QueryProductsResult {
            val forced = nextResult
            nextResult = null
            if (forced != null) return forced
            val filtered = if (passthrough) {
                skus.associateWith { BillingProduct(it, "<passthrough>", "USD", 0L) }
            } else {
                recognizedSkus.filterKeys { it in skus }
            }
            _products.value = filtered
            return QueryProductsResult.Success(products = filtered)
        }

        override fun snapshot(): Map<String, BillingProduct> = _products.value

        companion object {
            fun passthrough(): FakeBillingAvailability = FakeBillingAvailability(passthrough = true)
        }
    }

    /**
     * Programmable [SessionTracker]: emits a new session whenever
     * [roll] is called. Mirrors the production rollover behavior
     * without exercising the lifecycle observer plumbing — that's
     * covered by [SessionTrackerImplTest].
     */
    private class FakeSessionTracker(
        initial: Session,
    ) : SessionTracker {
        private val flow = MutableStateFlow(initial)
        override val current: Session get() = flow.value
        override fun observe(): Flow<Session> = flow

        fun roll(toId: Long) {
            flow.value = flow.value.copy(
                id = toId,
                reason = SessionStartReason.BackgroundRollover(
                    backgroundedForMs = SessionTracker.BACKGROUND_ROLLOVER_MS,
                ),
            )
        }
    }

    /**
     * In-memory [CacheFactory] that records what gets persisted.
     * Tests pre-seed [productsCache] to simulate prior-session disk
     * state, then assert what the repo writes back via [read].
     */
    private class RecordingCacheFactory : CacheFactory {
        val productsCache = RecordingCache<CachedProductCatalog>(default = CachedProductCatalog.EMPTY)

        override fun <T : Any> inMemory(defaultValue: () -> T): Cache<T> {
            return RecordingCache(defaultValue())
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> persistent(
            name: String,
            serializer: CacheJsonSerializer<T>,
            loadEagerly: Boolean,
        ): Cache<T> = when (name) {
            "products_catalog" -> productsCache as Cache<T>
            else -> RecordingCache(default = serializer.let {
                // Serializer's default isn't directly exposed; tests
                // don't need other caches yet so this branch is just
                // a defensive fallback.
                error("Unexpected persistent cache requested: $name")
            })
        }

        fun seedProductsCache(
            catalog: ProductCatalog,
            lastFetchSessionId: Long?,
            fetchedAtEpochMs: Long,
            serverNowEpochMs: Long,
        ) {
            productsCache.seed(
                CachedProductCatalog(
                    catalog = catalog,
                    lastFetchSessionId = lastFetchSessionId,
                    fetchedAtEpochMs = fetchedAtEpochMs,
                    serverNowEpochMs = serverNowEpochMs,
                ),
            )
        }
    }

    private class RecordingCache<T : Any>(default: T) : Cache<T> {
        private val state = MutableStateFlow(default)
        override val updates: Flow<T> = state
        override suspend fun get(): T = state.value
        override suspend fun set(value: T) { state.value = value }
        override suspend fun clear() { /* tests don't assert on clear */ }
        fun seed(value: T) { state.value = value }
        fun read(): T = state.value
    }

    private class FixedClock(private val nowMs: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
    }

    @OptIn(com.dangerfield.cards.libraries.networking.InternalNetworkingApi::class)
    private object StubNetworkClient : NetworkClient {
        override val client: HttpClient
            get() = error("FakeDataSource overrides fetchCatalog — should not reach HttpClient")
        override val authenticatedClient: HttpClient
            get() = error("not used")
        override suspend fun awaitAuthReady() = Unit
    }

    private suspend fun <T> Flow<T>.firstValue(): T = first()

    companion object {
        // Fixed wall-clock the FixedClock + seeded snapshots key off. Any
        // ms value works — picked something obviously synthetic so test
        // failures point clearly at this rather than at "now()".
        private const val NOW_MS: Long = 1_700_000_000_000L

        private val SAMPLE_DTO = ProductCatalogDto(
            chipPacks = listOf(
                ChipPackDto(
                    id = "chip_pack_small",
                    title = "Pocket Stack",
                    subtitle = "5,000 chips",
                    iconEmoji = "🪙",
                    grantsChips = 5_000,
                    store = StoreSkuDto("chips_small", "$0.99"),
                ),
            ),
        )

        private val SAMPLE_DOMAIN: ProductCatalog = ProductCatalog(
            chipPacks = listOf(
                Product.ChipPack(
                    id = "chip_pack_small",
                    title = "Pocket Stack",
                    subtitle = "5,000 chips",
                    iconEmoji = "🪙",
                    grantsChips = 5_000,
                    store = StoreSku("chips_small", "<passthrough>"),
                ),
            ),
        )
    }
}
