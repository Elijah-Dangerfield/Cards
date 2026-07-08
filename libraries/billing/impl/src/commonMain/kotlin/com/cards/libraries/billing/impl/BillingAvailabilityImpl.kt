package com.dangerfield.cards.libraries.billing.impl

import com.dangerfield.cards.libraries.billing.BillingAvailability
import com.dangerfield.cards.libraries.billing.BillingClient
import com.dangerfield.cards.libraries.billing.BillingProduct
import com.dangerfield.cards.libraries.billing.ConnectionState
import com.dangerfield.cards.libraries.billing.QueryProductsResult
import com.dangerfield.cards.libraries.core.logging.KLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Caches the most recent [BillingClient.queryProducts] result. The
 * catalog reconciliation reads the synchronous snapshot to filter +
 * overlay prices without taking a suspend hop on every emit.
 *
 * Single-flight refresh: the mutex serializes concurrent callers (shop
 * mount + catalog refresh) so the platform store sees one query, not
 * two. The cache is held across failed refreshes — a flaky store
 * doesn't blow away the previous successful product map.
 *
 * Lazy connect: [refresh] calls [BillingClient.connect] before the first
 * query so consumers don't have to remember to — the platform impls
 * handle the store handshake there.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class BillingAvailabilityImpl(
    private val billingClient: BillingClient,
) : BillingAvailability {

    private val logger = KLog.withTag("BillingAvailability")
    private val mutex = Mutex()
    private val _products = MutableStateFlow<Map<String, BillingProduct>>(emptyMap())

    override val products: Flow<Map<String, BillingProduct>> = _products.asStateFlow()
    override val connectionState: Flow<ConnectionState> = billingClient.connectionState

    override suspend fun refresh(skus: Set<String>): QueryProductsResult = mutex.withLock {
        if (skus.isEmpty()) {
            _products.value = emptyMap()
            return@withLock QueryProductsResult.Success(products = emptyMap())
        }
        if (billingClient.connectionState.value == ConnectionState.Disconnected) {
            billingClient.connect()
        }
        when (val result = billingClient.queryProducts(skus)) {
            is QueryProductsResult.Success -> {
                _products.value = result.products
                result
            }
            is QueryProductsResult.NotConnected -> {
                logger.w { "Billing not connected; keeping cached product map (size=${_products.value.size})" }
                result
            }
            is QueryProductsResult.Failed -> {
                logger.w { "queryProducts failed: ${result.message}" }
                result
            }
        }
    }

    override fun snapshot(): Map<String, BillingProduct> = _products.value
}
