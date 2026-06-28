package com.dangerfield.cards.libraries.billing.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.billing.BillingClient
import com.dangerfield.cards.libraries.billing.BillingProduct
import com.dangerfield.cards.libraries.billing.ConnectionState
import com.dangerfield.cards.libraries.billing.PurchaseResult
import com.dangerfield.cards.libraries.billing.QueryProductsResult
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the [BillingAvailabilityImpl] contract — the cache layer the shop
 * catalog reconciliation reads synchronously while filtering IAP packs.
 * The interesting invariants are all "what happens around a failed or
 * skipped fetch": the cached product map must NOT get wiped, and a
 * concurrent caller must NOT see two store queries fire.
 */
class BillingAvailabilityImplTest : CoroutineTest() {

    @Test
    fun refresh_emptySkus_emitsEmptyMap_andSkipsClientEntirely() = runUnitTest {
        val client = RecordingBillingClient(
            connection = ConnectionState.Connected,
            response = QueryProductsResult.Success(products = mapOf("a" to product("a"))),
        )
        val availability = BillingAvailabilityImpl(client)

        val result = availability.refresh(skus = emptySet())

        assertEquals(QueryProductsResult.Success(products = emptyMap()), result)
        assertEquals(emptyMap(), availability.snapshot())
        assertEquals(0, client.connectCalls, "empty skus must not trigger a connect")
        assertEquals(0, client.queryCalls.size, "empty skus must not trigger a query")
    }

    @Test
    fun refresh_disconnectedClient_callsConnectBeforeQuery() = runUnitTest {
        val product = product("chips_small")
        val client = RecordingBillingClient(
            connection = ConnectionState.Disconnected,
            response = QueryProductsResult.Success(products = mapOf(product.sku to product)),
            connectsTo = ConnectionState.Connected,
        )
        val availability = BillingAvailabilityImpl(client)

        val result = availability.refresh(skus = setOf(product.sku))

        assertEquals(QueryProductsResult.Success(products = mapOf(product.sku to product)), result)
        assertEquals(1, client.connectCalls, "Disconnected client must be connected before querying")
        assertEquals(listOf(setOf(product.sku)), client.queryCalls)
    }

    @Test
    fun refresh_connectedClient_skipsRedundantConnect() = runUnitTest {
        val product = product("chips_small")
        val client = RecordingBillingClient(
            connection = ConnectionState.Connected,
            response = QueryProductsResult.Success(products = mapOf(product.sku to product)),
        )
        val availability = BillingAvailabilityImpl(client)

        availability.refresh(skus = setOf(product.sku))

        assertEquals(0, client.connectCalls, "already-Connected client must not be re-connected")
        assertEquals(1, client.queryCalls.size)
    }

    @Test
    fun refresh_success_updatesProductsFlow_andSnapshot() = runUnitTest {
        val product = product("chips_medium")
        val client = RecordingBillingClient(
            connection = ConnectionState.Connected,
            response = QueryProductsResult.Success(products = mapOf(product.sku to product)),
        )
        val availability = BillingAvailabilityImpl(client)

        availability.products.test {
            assertEquals(emptyMap(), awaitItem(), "initial state is empty before any refresh")
            availability.refresh(skus = setOf(product.sku))
            assertEquals(mapOf(product.sku to product), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(mapOf(product.sku to product), availability.snapshot())
    }

    @Test
    fun refresh_notConnected_keepsCachedProducts() = runUnitTest {
        val client = StagedBillingClient(connection = ConnectionState.Connected)
        val availability = BillingAvailabilityImpl(client)

        val first = product("chips_small")
        client.nextResponse = QueryProductsResult.Success(products = mapOf(first.sku to first))
        availability.refresh(skus = setOf(first.sku))
        assertEquals(mapOf(first.sku to first), availability.snapshot())

        // A flaky store call returning NotConnected must NOT wipe what
        // we already had — the docstring explicitly calls this out.
        client.nextResponse = QueryProductsResult.NotConnected
        val result = availability.refresh(skus = setOf(first.sku))

        assertEquals(QueryProductsResult.NotConnected, result)
        assertEquals(
            mapOf(first.sku to first),
            availability.snapshot(),
            "transient NotConnected must not invalidate the cached product map",
        )
    }

    @Test
    fun refresh_failed_keepsCachedProducts() = runUnitTest {
        val client = StagedBillingClient(connection = ConnectionState.Connected)
        val availability = BillingAvailabilityImpl(client)

        val first = product("chips_small")
        client.nextResponse = QueryProductsResult.Success(products = mapOf(first.sku to first))
        availability.refresh(skus = setOf(first.sku))

        client.nextResponse = QueryProductsResult.Failed("network down")
        val result = availability.refresh(skus = setOf(first.sku))

        assertTrue(result is QueryProductsResult.Failed)
        assertEquals(
            mapOf(first.sku to first),
            availability.snapshot(),
            "transient Failed must not invalidate the cached product map",
        )
    }

    @Test
    fun refresh_isSingleFlight_concurrentCallsSerialize() = runUnitTest {
        // The mutex on refresh() is the only thing standing between the
        // store and a double-query when shop mount + catalog refresh land
        // at the same instant. Pin it: a second refresh() that arrives
        // while the first is suspended must wait, not race.
        val gate = CompletableDeferred<Unit>()
        val client = SuspendingBillingClient(
            connection = ConnectionState.Connected,
            gate = gate,
        )
        val availability = BillingAvailabilityImpl(client)

        val first = async { availability.refresh(skus = setOf("a")) }
        val second = async { availability.refresh(skus = setOf("a")) }

        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(1, client.inFlight, "second caller must be blocked on the mutex, not racing the first")

        gate.complete(Unit)
        first.await()
        second.await()

        assertEquals(2, client.totalCalls, "both refreshes resolve once the gate opens, one after the other")
        assertEquals(1, client.maxConcurrent, "mutex must serialize — peak in-flight queries is 1, never 2")
    }
}

private fun product(sku: String): BillingProduct = BillingProduct(
    sku = sku,
    displayPrice = "$0.99",
    currencyCode = "USD",
    priceMicros = 990_000,
)

private class RecordingBillingClient(
    connection: ConnectionState,
    private val response: QueryProductsResult,
    private val connectsTo: ConnectionState = connection,
) : BillingClient {
    private val _connectionState = MutableStateFlow(connection)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    var connectCalls: Int = 0
        private set
    val queryCalls: MutableList<Set<String>> = mutableListOf()

    override suspend fun connect(): ConnectionState {
        connectCalls += 1
        _connectionState.value = connectsTo
        return connectsTo
    }

    override suspend fun queryProducts(skus: Set<String>): QueryProductsResult {
        queryCalls += skus
        return response
    }

    override suspend fun purchase(sku: String, userId: String): PurchaseResult =
        PurchaseResult.NotConnected

    override suspend fun acknowledge(purchaseToken: String): Boolean = false
    override suspend fun consume(purchaseToken: String): Boolean = false
}

private class StagedBillingClient(connection: ConnectionState) : BillingClient {
    private val _connectionState = MutableStateFlow(connection)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    var nextResponse: QueryProductsResult = QueryProductsResult.NotConnected

    override suspend fun connect(): ConnectionState = _connectionState.value
    override suspend fun queryProducts(skus: Set<String>): QueryProductsResult = nextResponse
    override suspend fun purchase(sku: String, userId: String): PurchaseResult =
        PurchaseResult.NotConnected
    override suspend fun acknowledge(purchaseToken: String): Boolean = false
    override suspend fun consume(purchaseToken: String): Boolean = false
}

private class SuspendingBillingClient(
    connection: ConnectionState,
    private val gate: CompletableDeferred<Unit>,
) : BillingClient {
    private val _connectionState = MutableStateFlow(connection)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    var inFlight: Int = 0
        private set
    var maxConcurrent: Int = 0
        private set
    var totalCalls: Int = 0
        private set

    override suspend fun connect(): ConnectionState = _connectionState.value

    override suspend fun queryProducts(skus: Set<String>): QueryProductsResult {
        inFlight += 1
        if (inFlight > maxConcurrent) maxConcurrent = inFlight
        totalCalls += 1
        try {
            gate.await()
            return QueryProductsResult.Success(products = emptyMap())
        } finally {
            inFlight -= 1
        }
    }

    override suspend fun purchase(sku: String, userId: String): PurchaseResult =
        PurchaseResult.NotConnected
    override suspend fun acknowledge(purchaseToken: String): Boolean = false
    override suspend fun consume(purchaseToken: String): Boolean = false
}
