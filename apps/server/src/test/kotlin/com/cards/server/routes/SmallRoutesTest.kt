package com.dangerfield.cards.server.routes

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.server.domain.AppConfigSource
import com.dangerfield.cards.server.domain.AvatarPacks
import com.dangerfield.cards.server.domain.ServerBuild
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Pins the three "small but load-bearing" routes that didn't have tests
 * before: `/_health`, `/v1/app-config`, `/v1/avatars`.
 *
 * Why these matter even though they're tiny:
 *  - `/_health` is the Fly liveness probe — a regression here knocks the
 *    app offline. Worth pinning that it's the un-versioned literal path
 *    (`/_health`, not `/v1/_health`) and stays 200/JSON. It also reports
 *    the build it's running (ENG-71), so there are two contracts to hold:
 *    `ok` + 200 for everything that already consumes it, and the SHA /
 *    version / start time a staleness check reads.
 *  - `/v1/app-config` is the kill-switch + feature-flag surface. The
 *    invariants are: shape is a JSON object (never an array, never a
 *    primitive), empty object is fine, the tree is returned verbatim.
 *    Documented in the AppConfigRoutes header but not pinned.
 *  - `/v1/avatars` is anonymous on purpose — the response is identical
 *    for every caller (full registry; client filters locally against
 *    inventory) and the fetch needs to fly before the Supabase JWT
 *    lands during onboarding. Pin: anon-callable, full registry,
 *    public cache (CDN-friendly), short enough TTL that adding a pack
 *    is visible quickly.
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
    fun health_reportsTheBuildItIsRunning() = runTest {
        // The point of the endpoint beyond liveness: prod served weeks-old
        // code twice with green dashboards because the process couldn't say
        // what it was. A staleness check needs the SHA, and `uptimeSeconds`
        // answers "how long has this been up" off the same response.
        val startedAt = Instant.parse("2026-10-24T12:00:00Z")
        testApplication {
            application {
                installSerialization()
                routing {
                    healthRoutes(
                        build = ServerBuild(
                            commitSha = "abc123def456",
                            commitBranch = "main",
                            version = "0.6.0",
                            startedAt = startedAt,
                        ),
                        clock = object : Clock {
                            override fun now(): Instant = startedAt + 90.seconds
                        },
                    )
                }
            }
            val resp = createClient { }.get("/_health")
            assertEquals(HttpStatusCode.OK, resp.status)
            val raw = resp.bodyAsText()
            // Both deploy workflows pull the SHA out of the raw body with sed
            // rather than adding a jq dependency to the runner, so the
            // unspaced `"commitSha":"…"` form is part of the contract.
            assertTrue(
                raw.contains("\"commitSha\":\"abc123def456\""),
                "deploy workflows sed for this exact form, got: $raw",
            )
            val body = Json.parseToJsonElement(raw).jsonObject
            assertEquals("abc123def456", body["commitSha"]!!.jsonPrimitive.content)
            assertEquals("main", body["commitBranch"]!!.jsonPrimitive.content)
            assertEquals("0.6.0", body["version"]!!.jsonPrimitive.content)
            assertEquals("2026-10-24T12:00:00Z", body["startedAt"]!!.jsonPrimitive.content)
            assertEquals(90, body["uptimeSeconds"]!!.jsonPrimitive.content.toLong())
        }
    }

    @Test
    fun health_reportsRealBuildMetadata_notAPlaceholder() = runTest {
        // Guards the wiring, not the value: `ServerBuild.current` must read
        // the generated CardsBuildConfig, so a build that knows its SHA
        // reports it. The Docker image has no .git — the Dockerfile passes
        // COMMIT_SHA in as a build arg — and if that ever breaks the field is
        // still present and parseable, just "unknown", so a staleness check
        // gets a value it can compare instead of a missing key.
        testApplication {
            application {
                installSerialization()
                routing { healthRoutes() }
            }
            val resp = createClient { }.get("/_health")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(BuildInfo.commitSha, body["commitSha"]!!.jsonPrimitive.content)
            assertEquals(BuildInfo.versionName, body["version"]!!.jsonPrimitive.content)
            assertTrue(
                body["commitSha"]!!.jsonPrimitive.content.isNotBlank(),
                "commitSha must always be present — \"unknown\" is the floor, blank is a bug",
            )
            assertTrue(
                body["uptimeSeconds"]!!.jsonPrimitive.content.toLong() >= 0,
                "uptime must never read negative",
            )
            Instant.parse(body["startedAt"]!!.jsonPrimitive.content)
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
                routing { appConfigRoutes(source = AppConfigSource { _, _ -> tree }) }
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
                routing { appConfigRoutes(source = AppConfigSource { _, _ -> JsonObject(emptyMap()) }) }
            }
            val resp = createClient { }.get("/v1/app-config")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertTrue(body.isEmpty())
        }
    }

    // ---------- /v1/avatars ----------

    @Test
    fun avatars_returns200_anonymously() = runTest {
        // Anon access is the whole point — the picker can warm its
        // cache before the Supabase JWT lands during onboarding.
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                routing { avatarRoutes() }
            }
            val resp = createClient { }.get("/v1/avatars")
            assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    @Test
    fun avatars_returnsFullPackRegistry_independentOfInventory() = runTest {
        // The endpoint no longer joins inventory — it returns every
        // pack the catalog defines, and the client filters locally
        // against optimistic inventory state. Pins the contract that
        // every pack (starter + all paid) lands in the response.
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                routing { avatarRoutes() }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val resp = client.get("/v1/avatars")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val packIds = body["packs"]!!.jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertEquals(
                AvatarPacks.all.map { it.id },
                packIds,
                "endpoint must return the full registry in catalog order",
            )
        }
    }

    @Test
    fun avatars_eachPackCarries_unlockProductId() = runTest {
        // Client filters against this field — starter pack is null
        // (always available), premium packs carry their product id.
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                routing { avatarRoutes() }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val resp = client.get("/v1/avatars")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val packs = body["packs"]!!.jsonArray
            val starter = packs.single { it.jsonObject["id"]!!.jsonPrimitive.content == AvatarPacks.Starter.id }
            assertTrue(
                starter.jsonObject["unlockProductId"]?.jsonPrimitive?.isString != true,
                "starter pack's unlockProductId must be null",
            )
            val food = packs.single { it.jsonObject["id"]!!.jsonPrimitive.content == AvatarPacks.Food.id }
            assertEquals(
                AvatarPacks.Food.unlockProductId,
                food.jsonObject["unlockProductId"]!!.jsonPrimitive.content,
                "paid pack must carry its unlock product id",
            )
        }
    }

    @Test
    fun avatars_setsPublicCache_soCdnCanShareTheResponse() = runTest {
        // Response is identical for every caller — public cache lets
        // a CDN or shared proxy serve it without per-user revalidation.
        // Short TTL keeps a server-deploy that adds a new pack visible
        // quickly.
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                routing { avatarRoutes() }
            }
            val resp = createClient { }.get("/v1/avatars")
            assertEquals(HttpStatusCode.OK, resp.status)
            val cacheControl = resp.headers[HttpHeaders.CacheControl] ?: ""
            assertTrue(
                cacheControl.contains("public"),
                "expected `public` directive, got: $cacheControl",
            )
            assertTrue(
                cacheControl.contains("max-age=60"),
                "expected max-age=60, got: $cacheControl",
            )
        }
    }

}
