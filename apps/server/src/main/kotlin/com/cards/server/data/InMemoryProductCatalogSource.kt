package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.PlatformStore
import com.dangerfield.cards.server.domain.Product
import com.dangerfield.cards.server.domain.ProductCatalog
import com.dangerfield.cards.server.domain.ProductCatalogSource
import com.dangerfield.cards.server.http.ClientContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Hardcoded catalog for the dev server. Edit, restart, the change is live on
 * the next client refresh.
 *
 * Production swaps this out for a Postgres-backed source bound under the same
 * [@ContributesBinding] — the endpoint stays unchanged.
 *
 * V1 catalog: four chip packs covering the standard "small / medium / large
 * / mega" price ladder used by social-casino apps. Real price authority sits
 * with the platform store; the `fallbackPriceDisplay` here is the placeholder
 * the UI shows while the store fetch is in-flight or unavailable.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class InMemoryProductCatalogSource : ProductCatalogSource {

    override suspend fun read(context: ClientContext): ProductCatalog {
        val chipPacks = listOf(
            Product.ChipPack(
                id = "chip_pack_small",
                titleByLocale = mapOf("en" to "Pocket Stack", "es" to "Pila de bolsillo"),
                subtitleByLocale = mapOf("en" to "5,000 chips", "es" to "5.000 fichas"),
                iconKey = "chips_small",
                grantsChips = 5_000,
                store = PlatformStore(
                    ios = PlatformStore.StoreSku("com.cards.iap.chips.small", "$0.99"),
                    android = PlatformStore.StoreSku("chips_small", "$0.99"),
                ),
            ),
            Product.ChipPack(
                id = "chip_pack_medium",
                titleByLocale = mapOf("en" to "Tall Stack", "es" to "Pila alta"),
                subtitleByLocale = mapOf("en" to "30,000 chips", "es" to "30.000 fichas"),
                iconKey = "chips_medium",
                featured = true,
                badgeByLocale = mapOf("en" to "BEST VALUE", "es" to "MEJOR VALOR"),
                grantsChips = 30_000,
                store = PlatformStore(
                    ios = PlatformStore.StoreSku("com.cards.iap.chips.medium", "$4.99"),
                    android = PlatformStore.StoreSku("chips_medium", "$4.99"),
                ),
            ),
            Product.ChipPack(
                id = "chip_pack_large",
                titleByLocale = mapOf("en" to "Whale Stack", "es" to "Pila ballena"),
                subtitleByLocale = mapOf("en" to "80,000 chips", "es" to "80.000 fichas"),
                iconKey = "chips_large",
                badgeByLocale = mapOf("en" to "+20% BONUS", "es" to "+20% EXTRA"),
                grantsChips = 80_000,
                store = PlatformStore(
                    ios = PlatformStore.StoreSku("com.cards.iap.chips.large", "$9.99"),
                    android = PlatformStore.StoreSku("chips_large", "$9.99"),
                ),
            ),
            Product.ChipPack(
                id = "chip_pack_mega",
                titleByLocale = mapOf("en" to "High Roller", "es" to "Gran apostador"),
                subtitleByLocale = mapOf("en" to "250,000 chips", "es" to "250.000 fichas"),
                iconKey = "chips_mega",
                grantsChips = 250_000,
                store = PlatformStore(
                    ios = PlatformStore.StoreSku("com.cards.iap.chips.mega", "$19.99"),
                    android = PlatformStore.StoreSku("chips_mega", "$19.99"),
                ),
            ),
        ).filter { context.platform in it.platforms }

        // Chip-purchasable items live in the same catalog as IAP packs (one
        // API, one cache, one rendering pipeline). Each one joins to a
        // client-bundled asset via `iconKey` (visual) and `grantsKey`
        // (effect/inventory entry).
        //
        // V1 selection is intentionally small + cosmetic-only — no
        // gameplay-affecting items, per the V1 product spec (no P2W).
        // Real assets land in :libraries:ui when the design files are ready;
        // until then the client falls back to a placeholder emoji.
        val chipOffers = listOf(
            Product.ChipOffer(
                id = "emote_dance",
                titleByLocale = mapOf("en" to "Victory Dance", "es" to "Baile de victoria"),
                subtitleByLocale = mapOf("en" to "Avatar emote", "es" to "Emote de avatar"),
                iconKey = "emote_dance",
                costChips = 2_500,
                grantsKey = "emote.dance",
            ),
            Product.ChipOffer(
                id = "emote_tilt",
                titleByLocale = mapOf("en" to "Salty Shake", "es" to "Sacudida salada"),
                subtitleByLocale = mapOf("en" to "Avatar emote", "es" to "Emote de avatar"),
                iconKey = "emote_tilt",
                costChips = 2_500,
                grantsKey = "emote.tilt",
            ),
            Product.ChipOffer(
                id = "table_neon",
                titleByLocale = mapOf("en" to "Neon Table", "es" to "Mesa de neón"),
                subtitleByLocale = mapOf("en" to "Table theme", "es" to "Tema de mesa"),
                iconKey = "table_neon",
                featured = true,
                badgeByLocale = mapOf("en" to "NEW", "es" to "NUEVO"),
                costChips = 8_000,
                grantsKey = "table.neon",
            ),
            Product.ChipOffer(
                id = "title_bluff_master",
                titleByLocale = mapOf("en" to "Bluff Master", "es" to "Maestro del farol"),
                subtitleByLocale = mapOf("en" to "Player title", "es" to "Título de jugador"),
                iconKey = "title_bluff_master",
                costChips = 5_000,
                grantsKey = "title.bluff_master",
            ),
        ).filter { context.platform in it.platforms }

        return ProductCatalog(
            chipPacks = chipPacks,
            chipOffers = chipOffers,
        )
    }
}
