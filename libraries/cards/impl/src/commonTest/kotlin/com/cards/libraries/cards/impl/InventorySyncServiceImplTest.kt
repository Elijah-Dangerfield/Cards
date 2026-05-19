package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.ChipsRepository
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
 *  - No-op when there's nothing pending — saves a network round-trip.
 *  - All-Confirmed response → repo.markConfirmed gets the right ids, no
 *    chip movement.
 *  - Reverted outcomes → repo.revertPurchase called AND chips refunded.
 *  - Network failure → returns Result.failure, leaves rows Pending (no
 *    markConfirmed, no revert).
 *  - Unknown server-side outcome decays to leaving the row Pending.
 */
class InventorySyncServiceImplTest : CoroutineTest() {

    @Test
    fun emptyInventory_noOps_withoutHittingNetwork() = runUnitTest {
        val inv = FakeInventory(initial = emptyList())
        val chips = FakeChips()
        var hitCount = 0
        val service = buildService(
            inventory = inv,
            chips = chips,
            handler = { hitCount++; respondJson(""" {"results":[]} """) },
        )

        val result = service.sync()

        assertTrue(result.isSuccess)
        assertEquals(0, hitCount, "no pending → no network call")
        assertTrue(inv.markedConfirmed.isEmpty())
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
        return InventorySyncServiceImpl(inventory, chips, networkClient)
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

        override fun observeInventory(): Flow<List<InventoryItem>> = state.asStateFlow()
        override suspend fun getInventory(): List<InventoryItem> = state.value
        override suspend fun redeemChipOffer(productId: String, costChips: Long): RedeemResult =
            error("Not used in sync tests")
        override suspend fun markConfirmed(productIds: Collection<String>) {
            markedConfirmed += productIds
        }
        override suspend fun revertPurchase(productId: String) { reverted += productId }
        override suspend fun deleteAll() { }
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
