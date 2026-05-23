package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.domain.AppConfigSource
import com.dangerfield.cards.server.domain.AvatarPacks
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.OwnedItem
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the three "small but load-bearing" routes that didn't have tests
 * before: `/_health`, `/v1/app-config`, `/v1/avatars`.
 *
 * Why these matter even though they're tiny:
 *  - `/_health` is the Fly liveness probe — a regression here knocks the
 *    app offline. Worth pinning that it's the un-versioned literal path
 *    (`/_health`, not `/v1/_health`) and stays 200/JSON.
 *  - `/v1/app-config` is the kill-switch + feature-flag surface. The
 *    invariants are: shape is a JSON object (never an array, never a
 *    primitive), empty object is fine, the tree is returned verbatim.
 *    Documented in the AppConfigRoutes header but not pinned.
 *  - `/v1/avatars` must stay behind the JWT plugin (it's our rate-limit
 *    chokepoint per-user), must serve the curated starter pack as-is,
 *    and must cache long enough that pickers don't refetch on every
 *    open. A regression to "no cache" wouldn't break behavior but would
 *    burn quota; cheap to pin.
 */
class SmallRoutesTest {

    // ---------- /_health ----------

    @Test
    fun health_returns200_andOkJson() = runTest {
        testApplication {
            application {
                installSerialization()
                routing { healthRoutes() }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val resp = client.get("/_health")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(true, body["ok"]!!.jsonPrimitive.content.toBoolean())
        }
    }

    @Test
    fun health_path_isUnversioned_andDistinctFromV1Health() = runTest {
        // Fly's liveness probe is wired to /_health (not /v1/_health) —
        // hits before any /v1 routes exist, won't break on a /v1 deprecation.
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                routing { healthRoutes() }
            }
            val resp = createClient { }.get("/v1/_health")
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }
    }

    // ---------- /v1/app-config ----------

    @Test
    fun appConfig_returnsTreeVerbatim() = runTest {
        val tree = buildJsonObject {
            putJsonObject("identity") {
                put("googleSignInEnabled", true)
                put("appleSignInEnabled", false)
            }
            putJsonObject("rooms") {
                put("maxSeats", 6)
            }
        }
        testApplication {
            application {
                installSerialization()
                routing { appConfigRoutes(source = AppConfigSource { tree }) }
            }
            val resp = createClient { }.get("/v1/app-config")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(true, body["identity"]!!.jsonObject["googleSignInEnabled"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(false, body["identity"]!!.jsonObject["appleSignInEnabled"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(6, body["rooms"]!!.jsonObject["maxSeats"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun appConfig_emptyObject_isLegitimate() = runTest {
        // The header comment promises this: "empty object is a legitimate
        // response — it means 'use client defaults'." Pin it so a future
        // edit that 404s or 503s on an empty config gets caught.
        testApplication {
            application {
                installSerialization()
                routing { appConfigRoutes(source = AppConfigSource { JsonObject(emptyMap()) }) }
            }
            val resp = createClient { }.get("/v1/app-config")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertTrue(body.isEmpty())
        }
    }

    // ---------- /v1/avatars ----------

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    private val testVerifier = JWT.require(Algorithm.HMAC256(testSecret))
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .build()

    private fun validJwt(): String = JWT.create()
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .withSubject(userId.toString())
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
        .sign(Algorithm.HMAC256(testSecret))

    @Test
    fun avatars_returns401_withoutBearer() = runTest {
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { avatarRoutes(EmptyInventory) }
            }
            val resp = createClient { }.get("/v1/avatars")
            assertEquals(
                HttpStatusCode.Unauthorized, resp.status,
                "/v1/avatars must stay JWT-gated — required for per-user rate limiting",
            )
        }
    }

    @Test
    fun avatars_returnsStarterPack_forUserWithNoOwnedPacks() = runTest {
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { avatarRoutes(EmptyInventory) }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val resp = client.get("/v1/avatars") {
                header(HttpHeaders.Authorization, "Bearer ${validJwt()}")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val packs = body["packs"]!!.jsonArray
            // No owned premium packs → exactly the starter pack, with its
            // emojis. Pins the wire shape + the inventory-join contract.
            assertEquals(1, packs.size)
            val starter = packs[0].jsonObject
            assertEquals(AvatarPacks.Starter.id, starter["id"]!!.jsonPrimitive.content)
            assertEquals(
                AvatarPacks.Starter.emojis,
                starter["emojis"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun avatars_includesPremiumPack_whenInventoryContainsItsProduct() = runTest {
        // Regression pin for the picker bug where paid avatar packs
        // (avatars_food, avatars_animals, etc.) never surfaced even after
        // purchase: the catalog rows existed but no Pack with a matching
        // unlockProductId was registered, so the inventory join was a
        // no-op. Asserting one premium pack here is enough — the filter
        // is shared.
        val ownedFood = object : InventoryRepository {
            override suspend fun listOwned(userId: UserId): List<OwnedItem> = listOf(
                OwnedItem(
                    productId = "avatars_food",
                    costChipsAtPurchase = 4000,
                    purchasedAt = kotlin.time.Clock.System.now(),
                ),
            )

            override suspend fun recordPurchase(
                userId: UserId,
                productId: String,
                costChipsAtPurchase: Long,
                purchasedAt: kotlin.time.Instant,
            ): OwnedItem = error("unused")

            override suspend fun recordEarnedGrant(
                userId: UserId,
                productId: String,
                grantedAt: kotlin.time.Instant,
            ): OwnedItem = error("unused")

            override suspend fun deleteAllForUser(userId: UserId) = Unit
        }
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { avatarRoutes(ownedFood) }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val resp = client.get("/v1/avatars") {
                header(HttpHeaders.Authorization, "Bearer ${validJwt()}")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val packs = body["packs"]!!.jsonArray
            val packIds = packs.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertTrue(
                AvatarPacks.Starter.id in packIds,
                "starter pack must always be present, got: $packIds",
            )
            assertTrue(
                AvatarPacks.Food.id in packIds,
                "owned premium pack (food) must appear, got: $packIds",
            )
            val food = packs.single { it.jsonObject["id"]!!.jsonPrimitive.content == AvatarPacks.Food.id }
            assertEquals(
                AvatarPacks.Food.emojis,
                food.jsonObject["emojis"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun avatars_omitsUnownedPremiumPacks() = runTest {
        // The catalog should never leak unowned premium packs back to the
        // client. With an empty inventory the response is exactly Starter.
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { avatarRoutes(EmptyInventory) }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val resp = client.get("/v1/avatars") {
                header(HttpHeaders.Authorization, "Bearer ${validJwt()}")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val packIds = body["packs"]!!.jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertEquals(listOf(AvatarPacks.Starter.id), packIds)
        }
    }

    @Test
    fun avatars_setsPrivateCache_so_packsDontBleedAcrossUsers() = runTest {
        // Per-user payload (we join inventory), so a `private` cache
        // directive is required — otherwise a CDN could serve user A's
        // owned packs to user B. A short TTL keeps a new purchase
        // visible quickly without burning the picker on every open.
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { avatarRoutes(EmptyInventory) }
            }
            val resp = createClient { }.get("/v1/avatars") {
                header(HttpHeaders.Authorization, "Bearer ${validJwt()}")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val cacheControl = resp.headers[HttpHeaders.CacheControl] ?: ""
            assertTrue(
                cacheControl.contains("private"),
                "expected `private` directive, got: $cacheControl",
            )
            assertTrue(
                cacheControl.contains("max-age=60"),
                "expected max-age=60, got: $cacheControl",
            )
        }
    }

    private object EmptyInventory : InventoryRepository {
        override suspend fun listOwned(userId: UserId): List<OwnedItem> = emptyList()
        override suspend fun recordPurchase(
            userId: UserId,
            productId: String,
            costChipsAtPurchase: Long,
            purchasedAt: kotlin.time.Instant,
        ): OwnedItem = error("unused")

        override suspend fun recordEarnedGrant(
            userId: UserId,
            productId: String,
            grantedAt: kotlin.time.Instant,
        ): OwnedItem = error("unused")

        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }
}
