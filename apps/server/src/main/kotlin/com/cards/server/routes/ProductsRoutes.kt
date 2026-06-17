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
import kotlin.time.Clock

/**
 * `GET /v1/products` — the shop catalog endpoint.
 *
 * Reads the client's [ClientContext] from request headers (platform, locale,
 * country, app version), pulls the catalog from [ProductCatalogSource], and
 * serializes it with strings localized to the caller's preferences.
 *
 * Sale-window enforcement:
 *  - Products with [Product.availableUntilEpochMs] are filtered against the
 *    server's clock here. If a sale ended one second ago, server time, the
 *    product is gone from the response. The client device clock has zero
 *    influence — even a phone with its date rolled back gets the same
 *    server-current catalog.
 *  - The response includes the server's wall clock at generation time so
 *    the client can run a clock-spoof-resistant countdown UI by combining
 *    [ProductCatalogResponse.serverNowEpochMs] with a local monotonic
 *    anchor. See `CatalogTimeAnchor` on the client.
 *
 * Caching: usually 5 min, but tightened to never serve past a known
 * expiry. If the soonest-expiring product is 90s away, max-age drops to
 * 90s. Keeps the CDN useful (cache hits during stable periods) while
 * avoiding the "stale-by-15-minutes about-to-expire offer" footgun.
 *
 * Platform filtering happens at the source.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
fun Route.productsRoutes(source: ProductCatalogSource) {
    get("/v1/products") {
        val ctx = call.clientContext()
        val rawCatalog = source.read(ctx)
        val nowEpochMs = Clock.System.now().toEpochMilliseconds()

        // Filter out anything past its sale window per the server's clock.
        // Prestige grants have no sale window (they're owned, not sold), so they
        // pass through untouched.
        val visibleCatalog = ProductCatalog(
            chipPacks = rawCatalog.chipPacks.filter { it.availableUntilEpochMs == null || it.availableUntilEpochMs > nowEpochMs },
            chipOffers = rawCatalog.chipOffers.filter { it.availableUntilEpochMs == null || it.availableUntilEpochMs > nowEpochMs },
            prestige = rawCatalog.prestige,
        )

        // Cache-Control max-age = min(5 min, soonest expiry - now).
        // Never serve a cached response past a known expiry.
        val soonestExpiryMs = visibleCatalog.soonestExpiryEpochMs()
        val secondsUntilSoonestExpiry = soonestExpiryMs?.let {
            ((it - nowEpochMs) / 1000L).coerceAtLeast(1L)
        }
        val maxAgeSeconds = (secondsUntilSoonestExpiry ?: DEFAULT_MAX_AGE_SECONDS)
            .coerceAtMost(DEFAULT_MAX_AGE_SECONDS)
        call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = maxAgeSeconds.toInt()))

        call.respond(visibleCatalog.toDto(ctx, nowEpochMs))
    }
}

private const val DEFAULT_MAX_AGE_SECONDS: Long = 300L

private fun ProductCatalog.soonestExpiryEpochMs(): Long? {
    val packExpiries = chipPacks.mapNotNull { it.availableUntilEpochMs }
    val offerExpiries = chipOffers.mapNotNull { it.availableUntilEpochMs }
    val all = packExpiries + offerExpiries
    return all.minOrNull()
}

internal fun ProductCatalog.toDto(ctx: ClientContext, serverNowEpochMs: Long): ProductCatalogResponse =
    ProductCatalogResponse(
        serverNowEpochMs = serverNowEpochMs,
        chipPacks = chipPacks.map { it.toDto(ctx) },
        chipOffers = chipOffers.map { it.toDto(ctx) },
        prestige = prestige.map { it.toDto(ctx) },
    )

private fun Product.ChipPack.toDto(ctx: ClientContext): ChipPackDto = ChipPackDto(
    id = id,
    title = pickLocalized(titleByLocale, ctx.preferredLocales, default = id),
    subtitle = pickLocalized(subtitleByLocale, ctx.preferredLocales),
    iconEmoji = iconEmoji,
    grantsChips = grantsChips,
    store = store.forPlatform(ctx.platform).toDto(),
    featured = featured,
    badge = badgeByLocale?.let { pickLocalized(it, ctx.preferredLocales) }?.ifEmpty { null },
    availableUntilEpochMs = availableUntilEpochMs,
    isEquippable = isEquippable,
)

private fun Product.ChipOffer.toDto(ctx: ClientContext): ChipOfferDto = ChipOfferDto(
    id = id,
    title = pickLocalized(titleByLocale, ctx.preferredLocales, default = id),
    subtitle = pickLocalized(subtitleByLocale, ctx.preferredLocales),
    iconEmoji = iconEmoji,
    costChips = costChips,
    grantsKey = grantsKey,
    featured = featured,
    badge = badgeByLocale?.let { pickLocalized(it, ctx.preferredLocales) }?.ifEmpty { null },
    description = descriptionByLocale?.let { pickLocalized(it, ctx.preferredLocales) }?.ifEmpty { null },
    unlockLevel = unlockLevel,
    availableUntilEpochMs = availableUntilEpochMs,
    isEquippable = isEquippable,
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
