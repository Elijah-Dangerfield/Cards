package com.dangerfield.cards.libraries.products.impl

import com.dangerfield.cards.libraries.billing.BillingAvailability
import com.dangerfield.cards.libraries.billing.BillingProduct
import com.dangerfield.cards.libraries.billing.QueryProductsResult
import com.dangerfield.cards.libraries.cards.SessionTracker
import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.products.CatalogTimeAnchor
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.ProductsRepository
import com.dangerfield.cards.libraries.storage.Cache
import com.dangerfield.cards.libraries.storage.CacheFactory
import com.dangerfield.cards.libraries.storage.versionedJsonSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Disk-backed, session-aware product catalog. Reference implementation
 * of the **session-aware cache pattern** described in `AGENTS.md`:
 * persist on success, hydrate on init, refresh only on session
 * rollover, never block the first frame on a network call. New
 * server-driven-reference-data repos that want the same shape should
 * use this file as a template.
 *
 * **Lifecycle of a screen-load:**
 *  1. UI subscribes to [observeCatalog]. The flow replays the
 *     last-on-disk snapshot synchronously (loaded eagerly at init),
 *     so the shop renders something the moment composition runs —
 *     no full-screen spinner on warm starts.
 *  2. [SessionTracker] published the current session at app launch.
 *     If the persisted `lastFetchSessionId` doesn't match, the repo
 *     fires a background refresh on its own — consumers don't need
 *     to call [refresh] from their `init` block. (They still can
 *     for explicit triggers like pull-to-refresh; that path passes
 *     `force = true`.)
 *  3. Session rolls (cold boot or ≥ 15-min background, see
 *     [SessionTracker]) → repo fires another background refresh, so
 *     a user coming back to the app after lunch sees up-to-date
 *     inventory without touching anything.
 *
 * **Failure handling:** a failed refresh leaves the cached catalog
 * (and `lastFetchSessionId`) intact — the user keeps seeing what was
 * there, and the next session-rollover or pull-to-refresh tries again.
 * The cache is never poisoned by a flaky network.
 *
 * **Too-stale drop:** snapshots older than [MAX_SNAPSHOT_AGE] are
 * dropped at init. The catalog turns over on the order of days; a
 * week-old snapshot is the outer bound of "still mostly useful." Per
 * the read-policy decision (docs/decisions.md), sale-window products
 * are independently clock-anchored via [CatalogTimeAnchor], so an
 * expired sale gets filtered at the UI layer regardless of how stale
 * the snapshot is.
 *
 * **Store reconciliation:** after fetching the catalog we ask
 * [BillingAvailability] which IAP SKUs the platform store actually
 * recognizes. Packs whose SKU isn't in the store response are dropped
 * — server config can be ahead of the store (staging, unrolled
 * releases, region restrictions) and we'd rather hide a pack than
 * show one that fails at purchase time. SKUs the store knows about
 * have their [com.dangerfield.cards.libraries.products.StoreSku.fallbackPriceDisplay]
 * overwritten with the store's localized price string.
 *
 * The reconciliation is best-effort: a failed store query leaves the
 * catalog unchanged (fallback prices visible) rather than dropping
 * every IAP pack. While store listings aren't provisioned, the real
 * platform clients return an empty product map and every IAP pack is
 * hidden — the desired pre-launch state.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = ProductsRepository::class)
@ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
@Inject
class ProductsRepositoryImpl(
    private val dataSource: ProductsHttpDataSource,
    private val billingAvailability: BillingAvailability,
    private val sessionTracker: SessionTracker,
    private val clock: Clock,
    private val appScope: AppCoroutineScope,
    cacheFactory: CacheFactory,
) : ProductsRepository, AutoInit {

    private val logger = KLog.withTag("ProductsRepository")
    private val state = MutableStateFlow(ProductCatalog.Empty)
    private val timeAnchor = MutableStateFlow<CatalogTimeAnchor?>(null)
    private val isRefreshing = MutableStateFlow(false)
    private val refreshFailed = MutableStateFlow(false)
    private val refreshMutex = Mutex()

    /**
     * Persisted snapshot — the catalog the user saw last time plus the
     * metadata we need to decide whether to fetch again. File-backed
     * via `:libraries:storage` so cold-start-with-no-network shows
     * yesterday's shop instead of an empty grid.
     */
    private val cache: Cache<CachedProductCatalog> = cacheFactory.persistent(
        name = "products_catalog",
        serializer = versionedJsonSerializer(defaultValue = { CachedProductCatalog.EMPTY }),
        // Eager load so [hydrateFromDisk] can read synchronously into
        // the StateFlow on init — UI never sees the Empty default
        // unless the cache really is empty (fresh install).
        loadEagerly = true,
    )

    /**
     * Session id we last successfully refreshed under. Mirrored in
     * memory so the session-rollover trigger doesn't re-read the
     * cache on every emission. Null until either disk-hydration or
     * the first successful refresh runs.
     */
    private var lastFetchSessionId: Long? = null

    init {
        // Run hydration + session-observer wiring on the app scope so
        // (a) construction stays synchronous (DI graph doesn't suspend),
        // (b) the work survives any caller's coroutine scope dying,
        // and (c) the next session rollover re-triggers refresh without
        // a screen mediator.
        appScope.launch {
            hydrateFromDisk()
            observeSessionForRefresh()
        }
    }

    override fun observeCatalog(): StateFlow<ProductCatalog> = state.asStateFlow()

    override fun observeTimeAnchor(): Flow<CatalogTimeAnchor?> = timeAnchor.asStateFlow()

    override fun observeIsRefreshing(): Flow<Boolean> = isRefreshing.asStateFlow()

    override fun observeRefreshFailed(): Flow<Boolean> = refreshFailed.asStateFlow()

    override suspend fun refresh(force: Boolean): Result<ProductCatalog> = refreshMutex.withLock {
        // Single-flight: two callers racing share the in-flight call's
        // result. When the call lands, the StateFlow update fans out to
        // every subscriber regardless of who triggered the refresh.
        val currentSessionId = sessionTracker.current.id
        if (!force && lastFetchSessionId == currentSessionId) {
            // Already fetched this session — caller's "non-forced
            // refresh" intent (typically a screen-init backstop) is
            // satisfied by what's already in the flow.
            return@withLock Result.success(state.value)
        }
        isRefreshing.value = true
        refreshFailed.value = false
        try {
            doRefresh(currentSessionId).onFailure { failure ->
                logger.w(failure) { "Catalog refresh failed" }
                // Set before isRefreshing flips back so consumers keying
                // "first load finished" off the refreshing transition see
                // the failure flag already raised (SHOP-10).
                refreshFailed.value = true
            }
        } finally {
            isRefreshing.value = false
        }
    }

    private suspend fun doRefresh(currentSessionId: Long): Result<ProductCatalog> {
        return Catching {
            val dto = dataSource.fetchCatalog().getOrThrow()
            val rawCatalog = dto.toDomain()

            val storeQuery = queryStoreFor(rawCatalog)
            val reconciled = rawCatalog.reconcileAgainst(storeQuery.products)

            // A pack the store doesn't recognize silently vanishes from the
            // shop (reconcileAgainst drops it), which is exactly the failure
            // mode of an ASC/Play listing typo, an unapproved product, or a
            // region gap. Error level on purpose: SentryLogTree promotes it to
            // a Sentry event so the mismatch is visible before users report
            // "the shop is empty". Only when the store answered
            // authoritatively — a cached/offline snapshot dropping packs is
            // connectivity, not misconfiguration.
            if (storeQuery.authoritative) {
                val droppedSkus =
                    rawCatalog.chipPacks.map { it.store.sku }.toSet() - storeQuery.products.keys
                if (droppedSkus.isNotEmpty()) {
                    logger.e {
                        "Store did not recognize ${droppedSkus.size}/${rawCatalog.chipPacks.size} " +
                            "chip-pack SKU(s); hiding from shop: $droppedSkus. " +
                            "Check the store listing (missing / not approved / region-blocked)."
                    }
                }
            }

            // Anchor first so any synchronous UI subscriber sees both
            // updates in a consistent order. Anchor uses
            // serverNowEpochMs from the response paired with a local
            // monotonic mark inside `capture()` — clock-spoof
            // resistant.
            val anchor = CatalogTimeAnchor.capture(serverNowEpochMs = dto.serverNowEpochMs)
            timeAnchor.value = anchor
            state.value = reconciled
            lastFetchSessionId = currentSessionId

            // Persist for the next cold launch. Fire-and-forget — a
            // failed disk write doesn't break the in-memory cache, and
            // the next successful refresh will re-attempt the write.
            appScope.launch {
                Catching {
                    cache.set(
                        CachedProductCatalog(
                            catalog = reconciled,
                            lastFetchSessionId = currentSessionId,
                            fetchedAtEpochMs = clock.now().toEpochMilliseconds(),
                            serverNowEpochMs = dto.serverNowEpochMs,
                        ),
                    )
                }.logOnFailure { "Failed to persist catalog snapshot" }
            }

            reconciled
        }
    }

    /**
     * Read whatever the previous session left on disk, drop it if it's
     * past [MAX_SNAPSHOT_AGE], and seed [state] + [timeAnchor] so the
     * UI's first frame has real content.
     *
     * If hydration drops the snapshot (too old) or finds nothing, the
     * subsequent [observeSessionForRefresh] trigger fires a network
     * fetch on its own.
     */
    private suspend fun hydrateFromDisk() {
        val record = Catching { cache.get() }
            .logOnFailure { "Failed to read persisted catalog" }
            .getOrNull()
            ?: return
        if (record.catalog.isEmpty) return
        val ageMs = clock.now().toEpochMilliseconds() - record.fetchedAtEpochMs
        if (ageMs > MAX_SNAPSHOT_AGE.inWholeMilliseconds) {
            logger.i { "Dropping persisted catalog: ${ageMs / 1000}s old > ${MAX_SNAPSHOT_AGE}" }
            Catching { cache.clear() }
                .logOnFailure { "Failed to clear stale catalog snapshot" }
            return
        }
        // Reconstruct the time anchor against the *original* server
        // wall-clock so sale-window countdowns stay correct relative
        // to the moment of fetch — not relative to "now," which would
        // make all hydrated offers look fresh.
        timeAnchor.value = CatalogTimeAnchor.capture(serverNowEpochMs = record.serverNowEpochMs)
        state.value = record.catalog
        lastFetchSessionId = record.lastFetchSessionId
    }

    /**
     * Subscribe to [SessionTracker] for the lifetime of the app. On
     * any session change where we haven't fetched yet for that
     * session, kick a refresh on the app scope. `distinctUntilChangedBy`
     * collapses any duplicate session emissions (e.g. StateFlow's
     * replay).
     */
    private fun observeSessionForRefresh() {
        sessionTracker.observe()
            .distinctUntilChangedBy { it.id }
            .onEach { session ->
                if (lastFetchSessionId == session.id) return@onEach
                // Non-forced — refresh() also short-circuits if a race
                // (e.g. screen-init refresh fired before we did) won.
                refresh(force = false)
            }
            .launchIn(appScope)
    }

    /**
     * Best-effort store query. Returns an empty map when the store
     * call fails OR when there are no IAP packs in the catalog.
     * Failures are logged but don't propagate — the catalog gets the
     * fallback price treatment instead.
     *
     * [StoreQuery.authoritative] is true only when the store answered the
     * query itself — a cached snapshot (offline / store error) is not
     * evidence that a SKU doesn't exist, so mismatch reporting keys off it.
     */
    private suspend fun queryStoreFor(catalog: ProductCatalog): StoreQuery {
        val skus = catalog.chipPacks.map { it.store.sku }.toSet()
        if (skus.isEmpty()) return StoreQuery(emptyMap(), authoritative = false)
        return when (val result = billingAvailability.refresh(skus)) {
            is QueryProductsResult.Success -> StoreQuery(result.products, authoritative = true)
            is QueryProductsResult.NotConnected -> {
                logger.w { "Store not connected during catalog refresh; using cached snapshot" }
                StoreQuery(billingAvailability.snapshot(), authoritative = false)
            }
            is QueryProductsResult.Failed -> {
                logger.w { "Store query failed during catalog refresh (${result.message}); using cached snapshot" }
                StoreQuery(billingAvailability.snapshot(), authoritative = false)
            }
        }
    }

    private data class StoreQuery(
        val products: Map<String, BillingProduct>,
        val authoritative: Boolean,
    )

    private companion object {
        /**
         * Maximum age of a persisted snapshot before we drop it on
         * cold boot. Catalog turns over on the order of days; 7 days
         * is the outer edge of "still mostly accurate." Sale-window
         * products are independently clock-anchored, so an expired
         * sale gets dropped at the UI layer regardless.
         */
        val MAX_SNAPSHOT_AGE: Duration = 7.days
    }
}

/**
 * On-disk snapshot of the catalog plus the metadata the repo needs
 * to decide when to refetch. Versioned via [versionedJsonSerializer]
 * so adding fields stays compatible with snapshots written by older
 * builds (missing fields default).
 */
@Serializable
internal data class CachedProductCatalog(
    val catalog: ProductCatalog,
    /**
     * Session id under which this snapshot was fetched. Compared
     * against [SessionTracker.current.id] on each session boundary
     * — match = already fresh, mismatch = refresh.
     */
    val lastFetchSessionId: Long?,
    /**
     * Device wall-clock when the fetch landed. Used at cold boot to
     * drop snapshots past [ProductsRepositoryImpl.MAX_SNAPSHOT_AGE].
     */
    val fetchedAtEpochMs: Long,
    /**
     * Server wall-clock at the moment of the fetch. Used to
     * reconstruct the [CatalogTimeAnchor] on cold-start so
     * sale-window countdowns stay accurate against the original
     * server time rather than "now."
     */
    val serverNowEpochMs: Long,
) {
    companion object {
        val EMPTY: CachedProductCatalog = CachedProductCatalog(
            catalog = ProductCatalog.Empty,
            lastFetchSessionId = null,
            fetchedAtEpochMs = 0L,
            serverNowEpochMs = 0L,
        )
    }
}

/**
 * Drop ChipPacks whose SKU the platform store doesn't recognize and
 * overlay the store's localized price on the survivors. Pure function
 * for unit-testability — the repo just glues it onto the refresh path.
 *
 * When the store query succeeds with an empty [storeProducts] map
 * (store reachable, no recognized SKUs), every IAP pack is filtered
 * out. That's the "store listings not provisioned" baseline and it's
 * intentional — we'd rather hide IAP than show un-buyable products. A
 * billing client that reports
 * [QueryProductsResult.NotConnected] / [QueryProductsResult.Failed]
 * leaves the cached snapshot in place instead.
 */
internal fun ProductCatalog.reconcileAgainst(
    storeProducts: Map<String, BillingProduct>,
): ProductCatalog {
    val reconciledPacks = chipPacks.mapNotNull { pack ->
        val storeProduct = storeProducts[pack.store.sku] ?: return@mapNotNull null
        if (storeProduct.displayPrice == pack.store.fallbackPriceDisplay) {
            pack
        } else {
            pack.copy(
                store = pack.store.copy(fallbackPriceDisplay = storeProduct.displayPrice),
            )
        }
    }
    return copy(chipPacks = reconciledPacks)
}
