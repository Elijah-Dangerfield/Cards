package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.EquipmentEntry
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.EquipmentToggleResult
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.PurchaseState
import com.dangerfield.cards.libraries.cards.RedeemResult
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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

/**
 * Pins [InventorySyncServiceImpl] reconciliation logic via a Ktor MockEngine
 * stubbing the `/v1/inventory/sync` endpoint.
 *
 * What's pinned:
 *  - Empty-pending case still POSTs so the server's authoritative `owned`
 *    snapshot reaches the repo (cold-start / device-switch fetch).
 *  - All-Confirmed response → repo.markConfirmed gets the right ids, no
 *    chip movement.
 *  - `owned` snapshot is folded into the repo via applyServerSnapshot.
 *  - Reverted outcomes → repo.revertPurchase called AND chips refunded.
 *  - Network failure → returns Result.failure, leaves rows Pending (no
 *    markConfirmed, no revert).
 *  - Unknown server-side outcome decays to leaving the row Pending.
 */
class InventorySyncServiceImplTest : CoroutineTest() {

    @Test
    fun emptyInventory_stillPostsToFetchServerSnapshot() = runUnitTest {
        val inv = FakeInventory(initial = emptyList())
        val chips = FakeChips()
        var hitCount = 0
        val service = buildService(
            inventory = inv,
            chips = chips,
            handler = {
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
            },
        )

        val result = service.sync()

        assertTrue(result.isSuccess)
        assertEquals(1, hitCount, "empty pending must still fetch the owned snapshot")
        assertTrue(inv.markedConfirmed.isEmpty())
        assertEquals(
            listOf("emote_dance"),
            inv.snapshotsApplied.single().map { it.productId },
            "server's owned snapshot reaches the repo",
        )
    }

    @Test
    fun afterSnapshot_dropsOrphanEquipment_forUnownedProducts() = runUnitTest {
        // The bug this fixes: local equipment row for red_felt remains even
        // though the user no longer owns it on the server. The sync service
        // should call dropOrphanEquipment with the server's owned set so the
        // equipment row gets reconciled.
        val inv = FakeInventory(initial = emptyList())
        val chips = FakeChips()
        val equipment = FakeEquipment(
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
        val service = buildService(
            inventory = inv,
            chips = chips,
            equipment = equipment,
            handler = {
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
            },
        )

        service.sync()

        assertEquals(setOf("blue_back"), equipment.dropCalls.single())
        val remainingEquipment = equipment.getAll()
        assertEquals(listOf("blue_back"), remainingEquipment.map { it.productId })
    }

    @Test
    fun allConfirmed_callsMarkConfirmed_doesNotMoveChips() = runUnitTest {
        val inv = FakeInventory(
            initial = listOf(
                pendingItem("emote_dance", cost = 2_500),
                pendingItem("table_neon", cost = 12_000),
            ),
        )
        val chips = FakeChips()
        val service = buildService(
            inventory = inv,
            chips = chips,
            handler = {
                respondJson(
                    """
                    {
                      "schemaVersion": 1,
                      "results": [
                        {"productId":"emote_dance","outcome":"Confirmed"},
                        {"productId":"table_neon","outcome":"Confirmed"}
                      ]
                    }
                    """.trimIndent()
                )
            },
        )

        val result = service.sync()

        assertTrue(result.isSuccess)
        assertEquals(setOf("emote_dance", "table_neon"), inv.markedConfirmed.toSet())
        assertTrue(chips.deltas.isEmpty(), "no chip movement for Confirmed outcomes")
    }

    @Test
    fun revertedOutcome_refundsChips_andDeletesRow() = runUnitTest {
        val inv = FakeInventory(initial = listOf(pendingItem("table_neon", cost = 12_000)))
        val chips = FakeChips()
        val service = buildService(
            inventory = inv,
            chips = chips,
            handler = {
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
            },
        )

        val result = service.sync()

        assertTrue(result.isSuccess)
        assertEquals(listOf(12_000L), chips.deltas, "refund applied as positive delta")
        assertEquals(listOf("table_neon"), inv.reverted, "row deleted")
        assertTrue(inv.markedConfirmed.isEmpty(), "Reverted ids must NOT be in markConfirmed")
    }

    @Test
    fun revertedWithNoChipsToRefund_deletesRowWithoutChipMovement() = runUnitTest {
        // Server is allowed to revert without refund (e.g. fraud detection,
        // returning a 0 chip refund). Should still delete the row.
        val inv = FakeInventory(initial = listOf(pendingItem("table_neon", cost = 12_000)))
        val chips = FakeChips()
        val service = buildService(
            inventory = inv,
            chips = chips,
            handler = {
                respondJson(
                    """
                    {
                      "schemaVersion": 1,
                      "results": [{"productId":"table_neon","outcome":"Reverted"}]
                    }
                    """.trimIndent()
                )
            },
        )

        service.sync()

        assertTrue(chips.deltas.isEmpty(), "no refund → no chip movement")
        assertEquals(listOf("table_neon"), inv.reverted)
    }

    @Test
    fun networkFailure_returnsFailureResult_leavesRowsPending() = runUnitTest {
        val inv = FakeInventory(initial = listOf(pendingItem("emote_dance", cost = 2_500)))
        val chips = FakeChips()
        val service = buildService(
            inventory = inv,
            chips = chips,
            handler = { respond("", HttpStatusCode.InternalServerError) },
        )

        val result = service.sync()

        assertTrue(result.isFailure, "5xx surfaces as Result.failure")
        assertTrue(inv.markedConfirmed.isEmpty(), "no confirmation on failure")
        assertTrue(inv.reverted.isEmpty(), "no revert on failure")
        assertTrue(chips.deltas.isEmpty(), "no chip movement on failure")
    }

    @Test
    fun unknownOutcome_leavesRowPending() = runUnitTest {
        // Forward-compat: server adds a new outcome (e.g. "Deferred") the
        // client doesn't know. Don't crash, don't markConfirmed, don't revert.
        val inv = FakeInventory(initial = listOf(pendingItem("emote_dance", cost = 2_500)))
        val chips = FakeChips()
        val service = buildService(
            inventory = inv,
            chips = chips,
            handler = {
                respondJson(
                    """
                    {
                      "schemaVersion": 1,
                      "results": [{"productId":"emote_dance","outcome":"FromTheFuture"}]
                    }
                    """.trimIndent()
                )
            },
        )

        val result = service.sync()

        assertTrue(result.isSuccess, "unknown outcomes shouldn't fail the sync")
        assertTrue(inv.markedConfirmed.isEmpty(), "future outcome should not auto-confirm")
        assertTrue(inv.reverted.isEmpty())
    }

    // ---------- Scaffolding ----------

    private fun buildService(
        inventory: FakeInventory,
        chips: FakeChips,
        equipment: FakeEquipment = FakeEquipment(),
        handler: io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): InventorySyncServiceImpl {
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
        return InventorySyncServiceImpl(inventory, chips, equipment, networkClient)
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
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
    ) = InventoryItem(
        productId = productId,
        state = PurchaseState.Pending,
        purchasedAtEpochMs = purchasedAtEpochMs,
        costChipsAtPurchase = cost,
    )

    private class FakeInventory(initial: List<InventoryItem>) : InventoryRepository {
        private val state = MutableStateFlow(initial)
        val markedConfirmed = mutableListOf<String>()
        val reverted = mutableListOf<String>()
        val snapshotsApplied = mutableListOf<List<InventoryItem>>()

        override fun observeInventory(): Flow<List<InventoryItem>> = state.asStateFlow()
        override suspend fun getInventory(): List<InventoryItem> = state.value
        override suspend fun redeemChipOffer(productId: String, costChips: Long): RedeemResult =
            error("Not used in sync tests")
        override suspend fun markConfirmed(productIds: Collection<String>) {
            markedConfirmed += productIds
        }
        override suspend fun revertPurchase(productId: String) { reverted += productId }
        override suspend fun applyServerSnapshot(authoritative: List<InventoryItem>) {
            snapshotsApplied += authoritative
        }
        override suspend fun deleteAll() { }
    }

    private class FakeEquipment(initial: List<EquipmentEntry> = emptyList()) : EquipmentRepository {
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
    }

    private class FakeChips : ChipsRepository {
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
        override suspend fun deleteAll() { }
    }
}
