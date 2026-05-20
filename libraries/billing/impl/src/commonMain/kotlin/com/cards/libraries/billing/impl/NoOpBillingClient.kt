package com.dangerfield.cards.libraries.billing.impl

import com.dangerfield.cards.libraries.billing.BillingClient
import com.dangerfield.cards.libraries.billing.ConnectionState
import com.dangerfield.cards.libraries.billing.PurchaseResult
import com.dangerfield.cards.libraries.billing.QueryProductsResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * No-op [BillingClient]: [ConnectionState.Unavailable] after [connect],
 * empty product map for any query, [PurchaseResult.NotConnected] for
 * every purchase. Causes the catalog reconciliation to drop every IAP
 * pack and the shop to render only chip-funded offers.
 *
 * Not currently bound directly — [DevBillingClient] delegates here for
 * release builds. Once a real platform binding lands, both this class
 * and `DevBillingClient` become candidates for removal.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NoOpBillingClient : BillingClient {
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override suspend fun connect(): ConnectionState {
        _connectionState.value = ConnectionState.Unavailable
        return ConnectionState.Unavailable
    }

    override suspend fun queryProducts(skus: Set<String>): QueryProductsResult =
        QueryProductsResult.Success(products = emptyMap())

    override suspend fun purchase(sku: String, userId: String): PurchaseResult =
        PurchaseResult.NotConnected

    override suspend fun acknowledge(purchaseToken: String): Boolean = false
}
