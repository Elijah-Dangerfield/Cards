package com.dangerfield.cards.libraries.products.impl

import com.dangerfield.cards.libraries.networking.NetworkClient
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
 */
@SingleIn(AppScope::class)
@Inject
open class ProductsHttpDataSource(
    private val networkClient: NetworkClient,
) {
    /** Throws on network / non-2xx — wrap at the repo layer. */
    open suspend fun fetchCatalog(): ProductCatalogDto =
        networkClient.client.get("/v1/products").body()
}
