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
 * Production default — no store integration. Reports [ConnectionState.Unavailable]
 * once [connect] is called and an empty product map for any query, so the
 * catalog reconciliation drops every IAP pack and the shop screen renders
 * only chip-funded offers.
 *
 * This is the right default for builds without provisioned store listings
 * (the state we're in pre-launch) and for previews / unit tests. Swap it
 * out per-platform once Apple App Store Connect + Google Play Console
 * accounts are set up:
 *
 * ```
 * @ContributesBinding(AppScope::class, replaces = [NoOpBillingClient::class])
 * class PlayBillingClient(...) : BillingClient { ... }
 * ```
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
