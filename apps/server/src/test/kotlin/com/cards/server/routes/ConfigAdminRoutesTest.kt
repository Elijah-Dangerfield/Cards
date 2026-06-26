package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.domain.AppConfigAdminRepository
import com.dangerfield.cards.server.domain.AppConfigManifestRepository
import com.dangerfield.cards.server.domain.ConfigAuditRecord
import com.dangerfield.cards.server.domain.ConfigFlagRecord
import com.dangerfield.cards.server.domain.ManifestEntry
import com.dangerfield.cards.server.domain.ManifestVersion
import com.dangerfield.cards.server.domain.RuleConditions
import com.dangerfield.cards.server.domain.TargetingRule
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Route-level tests for the config admin API against a fake repository — pins
 * the `X-Admin-Token` gate and the request → repository wiring without a DB.
 */
class ConfigAdminRoutesTest {

    private val token = "secret-token"
    private val adminConfig = AdminConfig(apiToken = token, orphanAnonTtlDays = 30, staleRoomTtlHours = 6)

    private class FakeRepo : AppConfigAdminRepository {
        val flags = mutableListOf<ConfigFlagRecord>()
        var lastUpsertActor: String? = null

        override suspend fun listFlags(): List<ConfigFlagRecord> = flags
        override suspend fun upsertFlag(path: String, value: kotlinx.serialization.json.JsonElement, actor: String): ConfigFlagRecord {
            lastUpsertActor = actor
            val record = ConfigFlagRecord(path, value, updatedAtEpochMs = 0, rules = emptyList())
            flags.removeAll { it.path == path }
            flags += record
            return record
        }
        override suspend fun deleteFlag(path: String, actor: String): Boolean =
            flags.removeAll { it.path == path }
        override suspend fun upsertRule(rule: TargetingRule, actor: String): TargetingRule? = rule
        override suspend fun deleteRule(id: UUID, actor: String): Boolean = false
        override suspend fun listAudit(flagPath: String?, limit: Int): List<ConfigAuditRecord> = emptyList()
    }

    private class FakeManifestRepo : AppConfigManifestRepository {
        val byVersion = mutableMapOf<Int, List<ManifestEntry>>()
        override suspend fun upsertManifest(versionCode: Int, appVersion: String?, entries: List<ManifestEntry>): Int {
            byVersion[versionCode] = entries
            return entries.size
        }
        override suspend fun listVersions(): List<ManifestVersion> =
            byVersion.entries.sortedByDescending { it.key }.map { (vc, entries) ->
                ManifestVersion(vc, "0.$vc.0", capturedAtEpochMs = 0, flagCount = entries.size)
            }
        override suspend fun getManifest(versionCode: Int?): List<ManifestEntry> {
            val target = versionCode ?: byVersion.keys.maxOrNull() ?: return emptyList()
            return byVersion[target].orEmpty()
        }
    }

    private fun testApp(
        repo: AppConfigAdminRepository,
        manifest: AppConfigManifestRepository = FakeManifestRepo(),
        notifier: com.dangerfield.cards.server.domain.ConfigChangeNotifier =
            com.dangerfield.cards.server.domain.ConfigChangeNotifier {},
        block: suspend (io.ktor.client.HttpClient) -> Unit,
    ) =
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                routing { configAdminRoutes(adminConfig, repo, manifest, notifier) }
            }
            block(createClient { })
        }

    /** A manifest seeded with social.enabled (boolean) so writes can be type-checked. */
    private fun schemaManifest() = FakeManifestRepo().apply {
        byVersion[1] = listOf(
            ManifestEntry("social.enabled", "boolean", JsonPrimitive(false), null, null),
        )
    }

    @Test
    fun list_withoutToken_is401() = runTest {
        testApp(FakeRepo()) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/config").status)
        }
    }

    @Test
    fun list_withWrongToken_is401() = runTest {
        testApp(FakeRepo()) { client ->
            val resp = client.get("/v1/admin/config") { header("X-Admin-Token", "nope") }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun list_withToken_is200_andReturnsFlags() = runTest {
        val repo = FakeRepo().apply {
            flags += ConfigFlagRecord("social.enabled", JsonPrimitive(false), 0, emptyList())
        }
        testApp(repo) { client ->
            val resp = client.get("/v1/admin/config") { header("X-Admin-Token", token) }
            assertEquals(HttpStatusCode.OK, resp.status)
            val flags = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["flags"]!!.jsonArray
            assertEquals("social.enabled", flags.single().jsonObject["path"]!!.let { (it as JsonPrimitive).content })
        }
    }

    @Test
    fun upsertFlag_recordsActorHeader_andStoresValue() = runTest {
        val repo = FakeRepo()
        testApp(repo) { client ->
            val resp = client.put("/v1/admin/config/flags/rooms.maxSeats") {
                header("X-Admin-Token", token)
                header("X-Admin-Actor", "elijah")
                contentType(ContentType.Application.Json)
                setBody("""{"value": 6}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("elijah", repo.lastUpsertActor)
            assertEquals(1, repo.flags.size)
            assertEquals("rooms.maxSeats", repo.flags.single().path)
        }
    }

    @Test
    fun deleteFlag_missing_is404() = runTest {
        testApp(FakeRepo()) { client ->
            val resp = client.delete("/v1/admin/config/flags/does.notExist") {
                header("X-Admin-Token", token)
            }
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }
    }

    @Test
    fun manifest_uploadThenRead_roundTrips() = runTest {
        val repo = FakeRepo()
        val manifest = FakeManifestRepo()
        testApp(repo, manifest) { client ->
            val upload = client.put("/v1/admin/config/manifest") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {"versionCode": 42, "appVersion": "1.0.1", "entries": [
                      {"path": "social.enabled", "type": "boolean", "default": false}
                    ]}
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, upload.status)

            val read = client.get("/v1/admin/config/manifest?version=42") { header("X-Admin-Token", token) }
            val entries = Json.parseToJsonElement(read.bodyAsText()).jsonObject["entries"]!!.jsonArray
            assertEquals("social.enabled", (entries.single().jsonObject["path"] as JsonPrimitive).content)
            assertEquals("boolean", (entries.single().jsonObject["type"] as JsonPrimitive).content)
        }
    }

    @Test
    fun resolve_picksRuleForMatchingTarget_andFallsBackOtherwise() = runTest {
        // social.enabled base=false, with a rule "locale es → true".
        val esRule = TargetingRule(
            id = UUID.randomUUID(),
            flagPath = "social.enabled",
            priority = 0,
            value = JsonPrimitive(true),
            conditions = RuleConditions(locales = setOf("es")),
            enabled = true,
            description = "Spanish beta",
        )
        val repo = FakeRepo().apply {
            flags += ConfigFlagRecord("social.enabled", JsonPrimitive(false), 0, listOf(esRule))
        }
        testApp(repo) { client ->
            fun resolveBody(locale: String) = """{"platform": "android", "locale": "$locale"}"""
            suspend fun resolved(locale: String): JsonPrimitive {
                val resp = client.post("/v1/admin/config/resolve") {
                    header("X-Admin-Token", token)
                    contentType(ContentType.Application.Json)
                    setBody(resolveBody(locale))
                }
                assertEquals(HttpStatusCode.OK, resp.status)
                val flag = Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["flags"]!!.jsonArray
                    .single { (it.jsonObject["path"] as JsonPrimitive).content == "social.enabled" }
                    .jsonObject
                return flag["resolved"] as JsonPrimitive
            }

            assertEquals("true", resolved("es").content)
            assertEquals("false", resolved("en").content)
        }
    }

    @Test
    fun upsertFlag_rejectsWrongType_acceptsRightType() = runTest {
        testApp(FakeRepo(), schemaManifest()) { client ->
            val bad = client.put("/v1/admin/config/flags/social.enabled") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"value": 6}""")
            }
            assertEquals(HttpStatusCode.BadRequest, bad.status)

            val ok = client.put("/v1/admin/config/flags/social.enabled") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"value": true}""")
            }
            assertEquals(HttpStatusCode.OK, ok.status)
        }
    }

    @Test
    fun upsertRule_rejectsOutOfRangeRollout() = runTest {
        val repo = FakeRepo().apply {
            flags += ConfigFlagRecord("social.enabled", JsonPrimitive(false), 0, emptyList())
        }
        testApp(repo, schemaManifest()) { client ->
            val resp = client.put("/v1/admin/config/rules/${UUID.randomUUID()}") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"flagPath":"social.enabled","priority":0,"value":true,"conditions":{"rolloutPercent":150}}""")
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }

    @Test
    fun upsertFlag_notifiesOnSuccess() = runTest {
        var notified: String? = null
        val notifier = com.dangerfield.cards.server.domain.ConfigChangeNotifier { notified = it.flagPath }
        testApp(FakeRepo(), schemaManifest(), notifier) { client ->
            client.put("/v1/admin/config/flags/social.enabled") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"value": true}""")
            }
        }
        assertEquals("social.enabled", notified)
    }

    @Test
    fun resolve_requiresToken() = runTest {
        testApp(FakeRepo()) { client ->
            val resp = client.post("/v1/admin/config/resolve") {
                contentType(ContentType.Application.Json)
                setBody("""{"platform": "ios"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }
}
