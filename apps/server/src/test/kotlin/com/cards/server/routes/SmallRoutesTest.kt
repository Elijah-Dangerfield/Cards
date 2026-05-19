package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.domain.AppConfigSource
import com.dangerfield.cards.server.domain.AvatarStarterPack
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
                routing { avatarRoutes() }
            }
            val resp = createClient { }.get("/v1/avatars")
            assertEquals(
                HttpStatusCode.Unauthorized, resp.status,
                "/v1/avatars must stay JWT-gated — required for per-user rate limiting",
            )
        }
    }

    @Test
    fun avatars_returnsStarterPack_withBearer() = runTest {
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { avatarRoutes() }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val resp = client.get("/v1/avatars") {
                header(HttpHeaders.Authorization, "Bearer ${validJwt()}")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val starter = body["starter"]!!.jsonArray.map { it.jsonPrimitive.content }
            // The route serves AvatarStarterPack.values verbatim. Asserting
            // size + a few sentinel entries pins the contract without
            // brittling on every catalog tweak.
            assertEquals(AvatarStarterPack.values.size, starter.size)
            assertEquals(AvatarStarterPack.values, starter)
        }
    }

    @Test
    fun avatars_setsCacheControl_forOneDay() = runTest {
        // The avatar pack is static within a release. A long max-age keeps
        // the picker snappy and avoids burning quota when the user reopens
        // the edit-profile screen. 1 day is what the route's contract says.
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { avatarRoutes() }
            }
            val resp = createClient { }.get("/v1/avatars") {
                header(HttpHeaders.Authorization, "Bearer ${validJwt()}")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val cacheControl = resp.headers[HttpHeaders.CacheControl] ?: ""
            assertTrue(
                cacheControl.contains("max-age=86400"),
                "expected max-age=86400 (1 day), got: $cacheControl",
            )
        }
    }
}
