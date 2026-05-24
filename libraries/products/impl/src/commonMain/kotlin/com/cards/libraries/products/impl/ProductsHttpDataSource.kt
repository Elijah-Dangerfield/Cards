package com.dangerfield.cards.libraries.products.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.retry.RetryPolicy
import com.dangerfield.cards.libraries.networking.unauthedCall
import io.ktor.client.call.body
import io.ktor.client.request.get
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Thin wrapper around the catalog endpoint. Owns the URL path and the DTO
 * deserialization — nothing else. Caching + flow exposure live in
 * [ProductsRepositoryImpl] so this stays a pure I/O leaf.
 *
 * Headers (Accept-Language, X-Platform, etc.) are injected automatically
 * by [NetworkClient]'s DefaultRequest config — no per-call code needed.
 *
 * GET /v1/products is read-only and the response is fully snapshot-shaped,
 * so it's safe to retry on transient failures.
 */
@SingleIn(AppScope::class)
@Inject
open class ProductsHttpDataSource(
    private val networkClient: NetworkClient,
) {
    open suspend fun fetchCatalog(): Catching<ProductCatalogDto> =
        networkClient.unauthedCall(
            description = "products.catalog",
            retry = RetryPolicy.idempotent(),
        ) { client ->
            client.get("/v1/products").body()
        }
}
