package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.domain.AppConfigAdminRepository
import com.dangerfield.cards.server.domain.ConfigAuditRecord
import com.dangerfield.cards.server.domain.ConfigFlagRecord
import com.dangerfield.cards.server.domain.TargetingRule
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
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

    private fun testApp(repo: AppConfigAdminRepository, block: suspend (io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                routing { configAdminRoutes(adminConfig, repo) }
            }
            block(createClient { })
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
}
