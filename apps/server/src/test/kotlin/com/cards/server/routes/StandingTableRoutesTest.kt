package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.config.AccessControlConfig
import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.config.BillingConfig
import com.dangerfield.cards.server.config.SupabaseConfig
import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.di.ServerComponent
import com.dangerfield.cards.server.di.create
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.installApp
import com.dangerfield.cards.server.plugins.JwtVerification
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandingTableRoutesTest : DatabaseTest() {
    @Test fun createCapPatchAuthorizationAndArchive() = runBlocking {
        val owner = seedAuthUser(); val other = seedAuthUser()
        val component = ServerComponent::class.create(database,
            SupabaseConfig("https://integration-test.supabase.co", null),
            BillingConfig(null, "Sandbox", null, null, null))
        testApplication {
            application { installApp(component, JwtVerification.Static(RoomSocketTestAuth.verifier),
                AdminConfig(null, 30, 6), AccessControlConfig(null)) }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val statuses = List(4) {
                async {
                    client.post("/v1/standing-tables") {
                        header(HttpHeaders.Authorization, "Bearer ${RoomSocketTestAuth.jwt(owner)}")
                        contentType(ContentType.Application.Json)
                        setBody(CreateStandingTableRequest("Thursday Poker", 5000, 6, 4, "20:00", "America/New_York"))
                    }.status
                }
            }.awaitAll()
            assertEquals(3, statuses.count { it == HttpStatusCode.Created })
            assertEquals(1, statuses.count { it == HttpStatusCode.Conflict })
            val mine = client.get("/v1/standing-tables/mine") { header(HttpHeaders.Authorization, "Bearer ${RoomSocketTestAuth.jwt(owner)}") }.body<StandingTablesResponse>()
            val code = mine.tables.first().code
            val forbidden = client.patch("/v1/standing-tables/$code") {
                header(HttpHeaders.Authorization, "Bearer ${RoomSocketTestAuth.jwt(other)}")
                contentType(ContentType.Application.Json); setBody(PatchStandingTableRequest(name = "Stolen"))
            }
            assertEquals(HttpStatusCode.Forbidden, forbidden.status)
            val patched = client.patch("/v1/standing-tables/$code") {
                header(HttpHeaders.Authorization, "Bearer ${RoomSocketTestAuth.jwt(owner)}")
                contentType(ContentType.Application.Json); setBody(PatchStandingTableRequest(name = "New name"))
            }
            assertEquals("New name", patched.body<StandingTableDto>().name)
            val deleted = client.delete("/v1/standing-tables/$code") { header(HttpHeaders.Authorization, "Bearer ${RoomSocketTestAuth.jwt(owner)}") }
            assertEquals(HttpStatusCode.NoContent, deleted.status)
            assertTrue(component.standingTableRepository.exists(code))
            val after = client.get("/v1/standing-tables/mine") { header(HttpHeaders.Authorization, "Bearer ${RoomSocketTestAuth.jwt(owner)}") }.body<StandingTablesResponse>()
            assertEquals(2, after.tables.size)
        }
    }
}
