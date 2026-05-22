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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        invDao: FakeInventoryDao,
        chips: FakeChipsRepository,
        equipment: FakeEquipmentRepository = FakeEquipmentRepository(),
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
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
        }
        val networkClient = object : NetworkClient {
            override val client: HttpClient = client
            override val authenticatedClient: HttpClient = client
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

    private class FakeChipsRepository : ChipsRepository {
        val deltas = mutableListOf<Long>()
        private val state = MutableStateFlow(0L)
        override fun observeBalance(): Flow<Long> = state.asStateFlow()
        override suspend fun getBalance(): Long = state.value
        override suspend fun applyDelta(delta: Long, reason: String, idempotencyKey: String?) {
            deltas += delta
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
