package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.EquipmentEntry
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.EquipmentToggleResult
import com.dangerfield.cards.libraries.cards.PurchaseState
import com.dangerfield.cards.libraries.cards.storage.db.InventoryDao
import com.dangerfield.cards.libraries.cards.storage.db.InventoryEntity
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pins [InventoryRepositoryImpl.sync] reconciliation logic via a Ktor
 * MockEngine stubbing `/v1/inventory/sync`.
 *
 * What's pinned:
 *  - Empty-pending case still POSTs so the server's authoritative `owned`
 *    snapshot reaches local state.
 *  - All-Confirmed response → local Pending rows flip to Confirmed; no
 *    chip movement.
 *  - `owned` snapshot is folded into local state (inserted as Confirmed).
 *  - Reverted outcomes → row deleted AND chips refunded via ChipsRepository.
 *  - Network failure → returns Result.failure; rows stay Pending.
 *  - Unknown server-side outcome decays to leaving the row Pending.
 *  - After snapshot, orphan equipment is dropped (drift fix).
 */
class InventoryRepositoryImplSyncTest : CoroutineTest() {

    @Test
    fun emptyInventory_stillPostsToFetchServerSnapshot() = runUnitTest {
        val invDao = FakeInventoryDao()
        val chips = FakeChipsRepository()
        var hitCount = 0
        val repo = buildRepo(invDao, chips) {
            hitCount++
            respondJson(
                """
                {
                  "schemaVersion": 1,
                  "results": [],
                  "owned": [
                    {"productId":"emote_dance","costChipsAtPurchase":2500,"purchasedAtEpochMs":1000}
                  ]
                }
                """.trimIndent(),
            )
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertEquals(1, hitCount, "empty pending must still fetch the owned snapshot")
        assertEquals(
            listOf("emote_dance"),
            invDao.getAll().map { it.productId },
            "server's owned snapshot reaches local state as Confirmed",
        )
        assertEquals(PurchaseState.Confirmed.name, invDao.getAll().single().syncState)
    }

    @Test
    fun afterSnapshot_dropsOrphanEquipment_forUnownedProducts() = runUnitTest {
        val invDao = FakeInventoryDao()
        val chips = FakeChipsRepository()
        val equipment = FakeEquipmentRepository(
            initial = listOf(
                EquipmentEntry(
                    productId = "red_felt",
                    isEquipped = true,
                    syncState = com.dangerfield.cards.libraries.cards.EquipmentSyncState.Synced,
                    updatedAtEpochMs = 0,
                ),
                EquipmentEntry(
                    productId = "blue_back",
                    isEquipped = true,
                    syncState = com.dangerfield.cards.libraries.cards.EquipmentSyncState.Synced,
                    updatedAtEpochMs = 0,
                ),
            ),
        )
        val repo = buildRepo(invDao, chips, equipment) {
            respondJson(
                """
                {
                  "schemaVersion": 1,
                  "results": [],
                  "owned": [
                    {"productId":"blue_back","costChipsAtPurchase":2500,"purchasedAtEpochMs":1000}
                  ]
                }
                """.trimIndent(),
            )
        }

        repo.sync()

        assertEquals(setOf("blue_back"), equipment.dropCalls.single())
        assertEquals(listOf("blue_back"), equipment.getAll().map { it.productId })
    }

    @Test
    fun allConfirmed_flipsPendingToConfirmed_doesNotMoveChips() = runUnitTest {
        val invDao = FakeInventoryDao().apply {
            seed(pendingItem("emote_dance", cost = 2_500))
            seed(pendingItem("table_neon", cost = 12_000))
        }
        val chips = FakeChipsRepository()
        val repo = buildRepo(invDao, chips) {
            respondJson(
                """
                {
                  "schemaVersion": 1,
                  "results": [
                    {"productId":"emote_dance","outcome":"Confirmed"},
                    {"productId":"table_neon","outcome":"Confirmed"}
                  ],
                  "owned": [
                    {"productId":"emote_dance","costChipsAtPurchase":2500,"purchasedAtEpochMs":1000},
                    {"productId":"table_neon","costChipsAtPurchase":12000,"purchasedAtEpochMs":1000}
                  ]
                }
                """.trimIndent()
            )
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        val states = invDao.getAll().associate { it.productId to it.syncState }
        assertEquals(PurchaseState.Confirmed.name, states["emote_dance"])
        assertEquals(PurchaseState.Confirmed.name, states["table_neon"])
        assertTrue(chips.deltas.isEmpty(), "no chip movement for Confirmed outcomes")
    }

    @Test
    fun revertedOutcome_refundsChips_andDeletesRow() = runUnitTest {
        val invDao = FakeInventoryDao().apply { seed(pendingItem("table_neon", cost = 12_000)) }
        val chips = FakeChipsRepository()
        val repo = buildRepo(invDao, chips) {
            respondJson(
                """
                {
                  "schemaVersion": 1,
                  "results": [
                    {"productId":"table_neon","outcome":"Reverted","chipsToRefund":12000,"message":"insufficient funds at sync time"}
                  ]
                }
                """.trimIndent()
            )
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertEquals(listOf(12_000L), chips.deltas, "refund applied as positive delta")
        assertTrue(
            invDao.getAll().none { it.productId == "table_neon" },
            "Reverted row is deleted",
        )
    }

    @Test
    fun revertedWithNoChipsToRefund_deletesRowWithoutChipMovement() = runUnitTest {
        val invDao = FakeInventoryDao().apply { seed(pendingItem("table_neon", cost = 12_000)) }
        val chips = FakeChipsRepository()
        val repo = buildRepo(invDao, chips) {
            respondJson(
                """
                {
                  "schemaVersion": 1,
                  "results": [{"productId":"table_neon","outcome":"Reverted"}]
                }
                """.trimIndent()
            )
        }

        repo.sync()

        assertTrue(chips.deltas.isEmpty(), "no refund → no chip movement")
        assertTrue(invDao.getAll().none { it.productId == "table_neon" })
    }

    @Test
    fun transient5xxThenSuccess_succeedsAfterRetry() = runUnitTest {
        // inventory.sync runs under RetryPolicy.idempotent() — server upserts
        // by (userId, productId), so a transient 5xx should not bubble up.
        val invDao = FakeInventoryDao().apply { seed(pendingItem("emote_dance", cost = 2_500)) }
        val chips = FakeChipsRepository()
        var hitCount = 0
        val repo = buildRepo(invDao, chips) {
            hitCount++
            if (hitCount == 1) {
                respond("", HttpStatusCode.InternalServerError)
            } else {
                respondJson(
                    """
                    {
                      "schemaVersion": 1,
                      "results": [{"productId":"emote_dance","outcome":"Confirmed"}],
                      "owned": [{"productId":"emote_dance","costChipsAtPurchase":2500,"purchasedAtEpochMs":1000}]
                    }
                    """.trimIndent(),
                )
            }
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertEquals(2, hitCount, "1 transient 5xx + 1 successful retry")
        assertEquals(
            PurchaseState.Confirmed.name,
            invDao.getAll().single().syncState,
            "successful retry flips the pending row to Confirmed",
        )
    }

    @Test
    fun networkFailure_returnsFailureResult_leavesRowsPending() = runUnitTest {
        val invDao = FakeInventoryDao().apply { seed(pendingItem("emote_dance", cost = 2_500)) }
        val chips = FakeChipsRepository()
        val repo = buildRepo(invDao, chips) {
            respond("", HttpStatusCode.InternalServerError)
        }

        val result = repo.sync()

        assertTrue(result.isFailure, "5xx surfaces as Result.failure")
        assertEquals(
            PurchaseState.Pending.name,
            invDao.getAll().single().syncState,
            "row stays Pending on failure",
        )
        assertTrue(chips.deltas.isEmpty(), "no chip movement on failure")
    }

    @Test
    fun concurrentSyncs_shareSingleInFlightPost() = runUnitTest {
        // Regression: ShopVM init + Shop redeem + EditProfile init can
        // call sync() in quick succession. Today's single-flight gate
        // must dedupe — N concurrent callers share one in-flight POST,
        // not N back-to-back POSTs.
        //
        // Gate the first call inside the DAO (rather than the Ktor
        // handler) so the suspension is on a dispatcher the test scope
        // owns. The Ktor MockEngine internally dispatches off the test
        // scheduler, which makes a handler-suspended gate flaky.
        val gate = CompletableDeferred<Unit>()
        var getAllCalls = 0
        val invDao = GatedInventoryDao(gate = gate, onGetAllEntered = { getAllCalls++ })
        val chips = FakeChipsRepository()
        var hitCount = 0
        val repo = buildRepo(invDao, chips) {
            hitCount++
            respondJson(
                """
                {"schemaVersion":1,"results":[],"owned":[]}
                """.trimIndent(),
            )
        }

        val a = async { repo.sync() }
        val b = async { repo.sync() }
        runCurrent()

        // First sync hit the DAO and suspended on the gate. Second sync
        // saw the in-flight Deferred and awaited it — no second DAO
        // entry, no second POST.
        assertEquals(
            1, getAllCalls,
            "second sync() must reuse the in-flight call, not re-enter doSync()",
        )
        assertEquals(0, hitCount, "no POST yet — handler is downstream of the DAO gate")

        gate.complete(Unit)
        val resultA = a.await()
        val resultB = b.await()

        assertTrue(resultA.isSuccess && resultB.isSuccess)
        // Two concurrent callers, exactly one POST. The DAO call-count
        // can land >1 because applyServerSnapshot does its own getAll()
        // after the network response — that's still inside the one
        // in-flight doSync(), so it doesn't break the contract.
        assertEquals(1, hitCount, "exactly one POST for two concurrent sync() callers")
    }

    @Test
    fun sequentialSyncs_eachFireOwnPost() = runUnitTest {
        // Counterpart to the single-flight test: once a sync completes,
        // the next caller starts a fresh POST. Single-flight is about
        // *concurrent* callers, not "skip if recently synced".
        val invDao = FakeInventoryDao()
        val chips = FakeChipsRepository()
        var hitCount = 0
        val repo = buildRepo(invDao, chips) {
            hitCount++
            respondJson(
                """
                {"schemaVersion":1,"results":[],"owned":[]}
                """.trimIndent(),
            )
        }

        repo.sync()
        repo.sync()

        assertEquals(2, hitCount, "sequential sync() callers each get their own POST")
    }

    @Test
    fun unknownOutcome_leavesRowPending() = runUnitTest {
        val invDao = FakeInventoryDao().apply { seed(pendingItem("emote_dance", cost = 2_500)) }
        val chips = FakeChipsRepository()
        val repo = buildRepo(invDao, chips) {
            respondJson(
                """
                {
                  "schemaVersion": 1,
                  "results": [{"productId":"emote_dance","outcome":"FromTheFuture"}]
                }
                """.trimIndent()
            )
        }

        val result = repo.sync()

        assertTrue(result.isSuccess, "unknown outcomes shouldn't fail the sync")
        assertEquals(
            PurchaseState.Pending.name,
            invDao.getAll().single().syncState,
            "future outcome should not auto-confirm",
        )
    }

    // ---------- scaffolding ----------

    private fun buildRepo(
        invDao: InventoryDao,
        chips: FakeChipsRepository,
        equipment: FakeEquipmentRepository = FakeEquipmentRepository(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): InventoryRepositoryImpl {
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
            // Match production: 4xx/5xx throws so the retry predicate can see it.
            expectSuccess = true
        }
        @OptIn(com.dangerfield.cards.libraries.networking.InternalNetworkingApi::class)
        val networkClient = object : NetworkClient {
            override val client: HttpClient = client
            override val authenticatedClient: HttpClient = client
            override suspend fun awaitAuthReady() = Unit
        }
        return InventoryRepositoryImpl(
            inventoryDao = invDao,
            chipsRepository = chips,
            equipmentRepository = equipment,
            networkClient = networkClient,
            appScope = AppCoroutineScope(dispatchers),
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

    private fun pendingItem(
        productId: String,
        cost: Long,
        purchasedAtEpochMs: Long = 1_000,
    ) = InventoryEntity(
        productId = productId,
        syncState = PurchaseState.Pending.name,
        purchasedAtEpochMs = purchasedAtEpochMs,
        costChipsAtPurchase = cost,
    )

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
    }

    private class FakeInventoryDao : InventoryDao {
        private val rows = MutableStateFlow<List<InventoryEntity>>(emptyList())

        fun seed(entity: InventoryEntity) {
            rows.value = rows.value + entity
        }

        override fun observeAll(): Flow<List<InventoryEntity>> = rows.asStateFlow()
        override suspend fun getAll(): List<InventoryEntity> = rows.value
        override suspend fun getByProductId(productId: String): InventoryEntity? =
            rows.value.firstOrNull { it.productId == productId }
        override suspend fun getPending(): List<InventoryEntity> =
            rows.value.filter { it.syncState == "Pending" }

        override suspend fun insertIfMissing(entity: InventoryEntity): Long {
            if (rows.value.any { it.productId == entity.productId }) return -1L
            rows.value = rows.value + entity
            return rows.value.size.toLong()
        }

        override suspend fun insertAll(list: List<InventoryEntity>) {
            val byId = rows.value.associateBy { it.productId }.toMutableMap()
            for (entity in list) byId[entity.productId] = entity
            rows.value = byId.values.toList()
        }

        override suspend fun markConfirmed(productIds: Collection<String>) {
            rows.value = rows.value.map {
                if (it.productId in productIds) it.copy(syncState = "Confirmed") else it
            }
        }

        override suspend fun delete(productId: String) {
            rows.value = rows.value.filterNot { it.productId == productId }
        }

        override suspend fun deleteConfirmed(productIds: Collection<String>) {
            rows.value = rows.value.filterNot {
                it.productId in productIds && it.syncState == "Confirmed"
            }
        }

        override suspend fun deleteAll() {
            rows.value = emptyList()
        }
    }

    /**
     * Delegates to a [FakeInventoryDao] for behavior but blocks [getAll]
     * on a CompletableDeferred so the test can pin a sync() in-flight at
     * a known suspension point. The [onGetAllEntered] hook fires before
     * the await so the test can count entries from outside.
     */
    private class GatedInventoryDao(
        private val gate: CompletableDeferred<Unit>,
        private val onGetAllEntered: () -> Unit,
        private val delegate: FakeInventoryDao = FakeInventoryDao(),
    ) : InventoryDao {
        override fun observeAll(): Flow<List<InventoryEntity>> = delegate.observeAll()
        override suspend fun getAll(): List<InventoryEntity> {
            onGetAllEntered()
            gate.await()
            return delegate.getAll()
        }
        override suspend fun getByProductId(productId: String): InventoryEntity? =
            delegate.getByProductId(productId)
        override suspend fun getPending(): List<InventoryEntity> = delegate.getPending()
        override suspend fun insertIfMissing(entity: InventoryEntity): Long =
            delegate.insertIfMissing(entity)
        override suspend fun insertAll(rows: List<InventoryEntity>) = delegate.insertAll(rows)
        override suspend fun markConfirmed(productIds: Collection<String>) =
            delegate.markConfirmed(productIds)
        override suspend fun delete(productId: String) = delegate.delete(productId)
        override suspend fun deleteConfirmed(productIds: Collection<String>) =
            delegate.deleteConfirmed(productIds)
        override suspend fun deleteAll() = delegate.deleteAll()
    }

    private class FakeChipsRepository : ChipsRepository {
        val deltas = mutableListOf<Long>()
        private val state = MutableStateFlow<Long?>(0L)
        override val walletJustCreated = MutableStateFlow(false)
        override fun observeBalance(): Flow<Long?> = state.asStateFlow()
        override suspend fun getBalance(): Long? = state.value
        override suspend fun addChips(amount: Long, reason: String, idempotencyKey: String?) {
            deltas += +amount
        }
        override suspend fun subtractChips(amount: Long, reason: String, idempotencyKey: String?) {
            deltas += -amount
        }
        override suspend fun setBalance(authoritativeBalance: Long) {
            state.value = authoritativeBalance
        }
        override suspend fun deleteAll() {}
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
    }

    private class FakeEquipmentRepository(
        initial: List<EquipmentEntry> = emptyList(),
    ) : EquipmentRepository {
        private val state = MutableStateFlow(initial)
        val dropCalls = mutableListOf<Set<String>>()

        override fun observeEquipped(): Flow<List<EquipmentEntry>> = state.asStateFlow()
        override suspend fun getAll(): List<EquipmentEntry> = state.value
        override suspend fun equip(productId: String): EquipmentToggleResult = error("not used")
        override suspend fun unequip(productId: String): EquipmentToggleResult = error("not used")
        override suspend fun applyServerSnapshot(authoritative: List<EquipmentEntry>) {
            state.value = authoritative
        }
        override suspend fun dropOrphanEquipment(ownedProductIds: Set<String>): List<String> {
            dropCalls += ownedProductIds
            val orphans = state.value.map { it.productId }.filter { it !in ownedProductIds }
            state.value = state.value.filter { it.productId in ownedProductIds }
            return orphans
        }
        override suspend fun deleteAll() { state.value = emptyList() }
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
    }
}
