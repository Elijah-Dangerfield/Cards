package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.PlatformStore
import com.dangerfield.cards.server.domain.Product
import com.dangerfield.cards.server.domain.ProductCatalog
import com.dangerfield.cards.server.domain.ProductCatalogSource
import com.dangerfield.cards.server.http.ClientContext
import com.dangerfield.cards.server.http.clientContext
import com.dangerfield.cards.server.http.pickLocalized
import io.ktor.http.CacheControl
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /v1/products` — the shop catalog endpoint.
 *
 * Reads the client's [ClientContext] from request headers (platform, locale,
 * country, app version), pulls the catalog from [ProductCatalogSource], and
 * serializes it with strings localized to the caller's preferences.
 *
 * Caching: `Cache-Control: public, max-age=300` (5 min). Catalog rarely
 * changes within a session and the data isn't user-specific. CDN-friendly.
 *
 * Platform filtering happens at the source (catalogs may have
 * platform-exclusive products), so by the time we serialize, every item is
 * applicable to the caller. The serializer surfaces only the matching
 * [PlatformStore.StoreSku] for the caller's platform — the other half is
 * never put on the wire.
 */
fun Route.productsRoutes(source: ProductCatalogSource) {
    get("/v1/products") {
        val ctx = call.clientContext()
        val catalog = source.read(ctx)
        call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 300))
        call.respond(catalog.toDto(ctx))
    }
}

internal fun ProductCatalog.toDto(ctx: ClientContext): ProductCatalogResponse =
    ProductCatalogResponse(
        chipPacks = chipPacks.map { it.toDto(ctx) },
        chipOffers = chipOffers.map { it.toDto(ctx) },
    )

private fun Product.ChipPack.toDto(ctx: ClientContext): ChipPackDto = ChipPackDto(
    id = id,
    title = pickLocalized(titleByLocale, ctx.preferredLocales, default = id),
    subtitle = pickLocalized(subtitleByLocale, ctx.preferredLocales),
    iconKey = iconKey,
    grantsChips = grantsChips,
    store = store.forPlatform(ctx.platform).toDto(),
    featured = featured,
    badge = badgeByLocale?.let { pickLocalized(it, ctx.preferredLocales) }?.ifEmpty { null },
)

private fun Product.ChipOffer.toDto(ctx: ClientContext): ChipOfferDto = ChipOfferDto(
    id = id,
    title = pickLocalized(titleByLocale, ctx.preferredLocales, default = id),
    subtitle = pickLocalized(subtitleByLocale, ctx.preferredLocales),
    iconKey = iconKey,
    costChips = costChips,
    grantsKey = grantsKey,
    featured = featured,
    badge = badgeByLocale?.let { pickLocalized(it, ctx.preferredLocales) }?.ifEmpty { null },
)

private fun PlatformStore.forPlatform(platform: ClientContext.Platform): PlatformStore.StoreSku =
    when (platform) {
        ClientContext.Platform.iOS -> ios
        ClientContext.Platform.Android -> android
        // Unknown platform: pick a platform deterministically rather than 500-ing.
        // The web preview / curl debug case lands here.
        ClientContext.Platform.Other -> android
    }

private fun PlatformStore.StoreSku.toDto(): StoreSkuDto = StoreSkuDto(
    sku = sku,
    fallbackPriceDisplay = fallbackPriceDisplay,
)
