package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.ProductsTable
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.PlatformStore
import com.dangerfield.cards.server.domain.Product
import com.dangerfield.cards.server.domain.ProductCatalog
import com.dangerfield.cards.server.domain.ProductCatalogSource
import com.dangerfield.cards.server.http.ClientContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Postgres-backed product catalog. Reads every row from the `products`
 * table on each call — the catalog is small (tens of rows), reads are
 * cheap, and the HTTP layer already wraps the response in a 5-minute
 * `Cache-Control` header, so we don't add an extra in-process cache.
 *
 * The seed in `V5__products.sql` is the single source of truth for the
 * V1 catalog. An admin can `UPDATE products SET …` (or insert new rows)
 * without a server redeploy; the next `GET /v1/products` reflects it
 * after the CDN cache window expires.
 *
 * Localization, platform filtering, and time-based offer expiry stay in
 * the route layer (where they always lived). This source is purely a
 * read-through of the seeded rows.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class PostgresProductCatalogSource(
    private val database: Database,
) : ProductCatalogSource {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun read(context: ClientContext): ProductCatalog =
        database.transaction {
            val rows = ProductsTable
                .selectAll()
                .orderBy(ProductsTable.sortOrder to SortOrder.ASC)
                .toList()

            // Pull platforms separately — TEXT[] doesn't have a first-class
            // Exposed column type and the catalog is small enough that a
            // second pass is trivial. Keyed by product id for the join below.
            val platformsById = readPlatforms(rows.map { it[ProductsTable.id] })

            val chipPacks = mutableListOf<Product.ChipPack>()
            val chipOffers = mutableListOf<Product.ChipOffer>()
            // unlock_only cosmetics (badges + titles) — kept out of the shop
            // buckets so they never render as buyable, but surfaced so clients
            // can resolve an owned badge's/title's display metadata.
            val prestige = mutableListOf<Product.ChipOffer>()

            for (row in rows) {
                val id = row[ProductsTable.id]
                val platforms = platformsById[id] ?: defaultPlatforms()
                val unlockOnly = row[ProductsTable.unlockOnly]
                when (val kind = row[ProductsTable.kind]) {
                    "chip_pack" -> if (!unlockOnly) chipPacks += row.toChipPack(platforms)
                    "chip_offer" -> {
                        val offer = row.toChipOffer(platforms)
                        if (unlockOnly) prestige += offer else chipOffers += offer
                    }
                    else -> error("Unknown product kind '$kind' on row id='$id'")
                }
            }

            ProductCatalog(chipPacks = chipPacks, chipOffers = chipOffers, prestige = prestige)
        }

    override suspend fun readById(id: String, context: ClientContext): Product? =
        database.transaction {
            val row = ProductsTable
                .selectAll()
                .where { ProductsTable.id eq id }
                .singleOrNull()
                ?: return@transaction null

            val platforms = readPlatforms(listOf(id))[id] ?: defaultPlatforms()
            when (val kind = row[ProductsTable.kind]) {
                "chip_pack" -> row.toChipPack(platforms)
                "chip_offer" -> row.toChipOffer(platforms)
                else -> error("Unknown product kind '$kind' on row id='$id'")
            }
        }

    private fun readPlatforms(ids: List<String>): Map<String, Set<ClientContext.Platform>> {
        if (ids.isEmpty()) return emptyMap()
        // Exposed has no first-class column type for `text[]`, so we run a
        // raw SELECT and decode the JDBC Array ourselves. Reading the
        // entire `products` table is fine — the catalog is tiny.
        val result = mutableMapOf<String, Set<ClientContext.Platform>>()
        TransactionManager.current().exec("SELECT id, platforms FROM products") { rs ->
            while (rs.next()) {
                val id = rs.getString(1)
                val array = rs.getArray(2)
                val names = (array?.array as? Array<*>)
                    ?.mapNotNull { it as? String }
                    ?: emptyList()
                result[id] = names.mapNotNull { parsePlatform(it) }.toSet()
            }
        }
        return result
    }

    private fun parsePlatform(name: String): ClientContext.Platform? = when (name) {
        "Android" -> ClientContext.Platform.Android
        "iOS" -> ClientContext.Platform.iOS
        "Other" -> ClientContext.Platform.Other
        else -> null
    }

    private fun defaultPlatforms(): Set<ClientContext.Platform> = setOf(
        ClientContext.Platform.Android,
        ClientContext.Platform.iOS,
        ClientContext.Platform.Other,
    )

    private fun ResultRow.toChipPack(
        platforms: Set<ClientContext.Platform>,
    ): Product.ChipPack = Product.ChipPack(
        id = this[ProductsTable.id],
        titleByLocale = parseLocalized(this[ProductsTable.titleByLocale]),
        subtitleByLocale = parseLocalized(this[ProductsTable.subtitleByLocale]),
        iconEmoji = this[ProductsTable.iconEmoji],
        featured = this[ProductsTable.featured],
        badgeByLocale = this[ProductsTable.badgeByLocale]?.let(::parseLocalized),
        availableUntilEpochMs = this[ProductsTable.availableUntilEpochMs],
        platforms = platforms,
        isEquippable = this[ProductsTable.isEquippable],
        grantsChips = requireNotNull(this[ProductsTable.grantsChips]) {
            "chip_pack row ${this[ProductsTable.id]} missing grants_chips (check constraint should prevent this)"
        },
        store = PlatformStore(
            ios = PlatformStore.StoreSku(
                sku = requireNotNull(this[ProductsTable.iosSku]) {
                    "chip_pack row ${this[ProductsTable.id]} missing ios_sku"
                },
                fallbackPriceDisplay = requireNotNull(this[ProductsTable.iosFallbackPrice]) {
                    "chip_pack row ${this[ProductsTable.id]} missing ios_fallback_price"
                },
            ),
            android = PlatformStore.StoreSku(
                sku = requireNotNull(this[ProductsTable.androidSku]) {
                    "chip_pack row ${this[ProductsTable.id]} missing android_sku"
                },
                fallbackPriceDisplay = requireNotNull(this[ProductsTable.androidFallbackPrice]) {
                    "chip_pack row ${this[ProductsTable.id]} missing android_fallback_price"
                },
            ),
        ),
    )

    private fun ResultRow.toChipOffer(
        platforms: Set<ClientContext.Platform>,
    ): Product.ChipOffer = Product.ChipOffer(
        id = this[ProductsTable.id],
        titleByLocale = parseLocalized(this[ProductsTable.titleByLocale]),
        subtitleByLocale = parseLocalized(this[ProductsTable.subtitleByLocale]),
        iconEmoji = this[ProductsTable.iconEmoji],
        featured = this[ProductsTable.featured],
        badgeByLocale = this[ProductsTable.badgeByLocale]?.let(::parseLocalized),
        availableUntilEpochMs = this[ProductsTable.availableUntilEpochMs],
        platforms = platforms,
        costChips = requireNotNull(this[ProductsTable.costChips]) {
            "chip_offer row ${this[ProductsTable.id]} missing cost_chips"
        },
        grantsKey = requireNotNull(this[ProductsTable.grantsKey]) {
            "chip_offer row ${this[ProductsTable.id]} missing grants_key"
        },
        unlockLevel = this[ProductsTable.unlockLevel],
        descriptionByLocale = this[ProductsTable.descriptionByLocale]?.let(::parseLocalized),
        isEquippable = this[ProductsTable.isEquippable],
    )

    private fun parseLocalized(raw: String): Map<String, String> {
        val obj = json.parseToJsonElement(raw) as? JsonObject
            ?: error("Localized field is not a JSON object: $raw")
        return obj.mapValues { it.value.jsonPrimitive.content }
    }
}
