package com.dangerfield.cards.server.routes

import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.data.InMemoryRoomService
import com.dangerfield.cards.server.domain.NoOpStandingTableRepository
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.StandingTableRepository
import com.dangerfield.cards.server.domain.UpdateProfileOutcome
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.sampleStandingTable
import com.dangerfield.cards.server.http.ClientContext
import com.dangerfield.cards.server.plugins.installRateLimits
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class SharePageRoutesTest {
    private val now = Instant.parse("2026-11-14T12:00:00Z")
    private val clock = object : Clock { override fun now() = now }
    private val table = sampleStandingTable().copy(name = "<script> & \"Poker\"")
    private val tables = object : StandingTableRepository by NoOpStandingTableRepository {
        override suspend fun find(code: String) = table.takeIf { it.code == code }
    }
    private val profiles = ShareTestProfiles(table.ownerUserId!!, "<script>Owner</script>", now)

    @Test fun escapesNamesAndSupportsBareCodeOnlyOnJoinHost() = testApplication {
        val rooms = InMemoryRoomService(clock)
        application {
            installRateLimits(AdminConfig(null, 30, 6))
            routing { sharePageRoutes(tables, rooms, profiles, clock) }
        }
        val response = client.get("/j/K7M2QX")
        assertEquals(HttpStatusCode.OK, response.status)
        val html = response.bodyAsText()
        assertTrue(html.contains("<title>&lt;script&gt; &amp; &quot;Poker&quot; · Downcard</title>"))
        assertTrue(html.contains("content=\"&lt;script&gt; &amp; &quot;Poker&quot; · Downcard\""))
        assertTrue(html.contains("<h1>&lt;script&gt; &amp; &quot;Poker&quot;</h1>"))
        assertTrue(html.contains("&lt;script&gt;Owner&lt;/script&gt;"))
        assertFalse(html.contains("<script>Owner"))
        assertEquals(HttpStatusCode.OK, client.get("/K7M2QX") { header(HttpHeaders.Host, "join.downcard.app") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/K7M2QX").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/j/IIIIII").status)
        assertEquals(HttpStatusCode.OK, client.get("/j/AAAAAA").status)
        val png = client.get("/j/K7M2QX/preview.png")
        assertEquals("public, max-age=300", png.headers[HttpHeaders.CacheControl])
        assertTrue(client.get("/j/K7M2QX/calendar.ics").bodyAsText().contains("RRULE:FREQ=WEEKLY;BYDAY=TH"))
    }

    @Test fun archivedTableDoesNotAdvertiseItsStillLoadedRoom() = testApplication {
        val archived = table.copy(archivedAt = now)
        val archivedTables = object : StandingTableRepository by NoOpStandingTableRepository {
            override suspend fun find(code: String) = archived.takeIf { it.code == code }
        }
        val rooms = InMemoryRoomService(clock)
        rooms.createWithCode(table.code, table.ownerUserId!!, "Host", RoomSettings.forBuyIn(5000, 6))
        application {
            installRateLimits(AdminConfig(null, 30, 6))
            routing { sharePageRoutes(archivedTables, rooms, profiles, clock) }
        }
        val response = client.get("/j/${table.code}")
        assertEquals(HttpStatusCode.OK, response.status)
        val html = response.bodyAsText()
        assertTrue(html.contains("<h1>This table isn&#39;t open right now</h1>"))
        assertFalse(html.contains("1 seated"))
        assertFalse(html.contains("id=\"calendar\""))
        assertEquals(HttpStatusCode.NotFound, client.get("/j/${table.code}/calendar.ics").status)
    }

    @Test fun enforcesSixtyRequestsPerIpPerMinute() = testApplication {
        application {
            installRateLimits(AdminConfig(null, 30, 6))
            routing { sharePageRoutes(tables, InMemoryRoomService(clock), profiles, clock) }
        }
        repeat(60) { assertEquals(HttpStatusCode.OK, client.get("/j/AAAAAA").status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/j/AAAAAA").status)
        assertEquals(HttpStatusCode.OK, client.get("/j/AAAAAA") { header("Fly-Client-IP", "192.0.2.25") }.status)
    }

    @Test fun savesEvidenceFetchedFromRealLocalServerWithoutExternalServices() = runBlocking {
        val rooms = InMemoryRoomService(clock)
        rooms.createWithCode("ABCD23", table.ownerUserId!!, "Host", RoomSettings.forBuyIn(5000, 6))
        val evidenceTable = table.copy(name = "Thursday Night Poker")
        val evidenceTables = object : StandingTableRepository by NoOpStandingTableRepository {
            override suspend fun find(code: String) = evidenceTable.takeIf { it.code == code }
        }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            installRateLimits(AdminConfig(null, 30, 6))
            routing { sharePageRoutes(evidenceTables, rooms, ShareTestProfiles(table.ownerUserId!!, "Maexj", now), clock) }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val base = "http://127.0.0.1:$port"
            val directory = File("build/share-evidence").apply { mkdirs() }
            val http = HttpClient.newHttpClient()
            for ((path, filename) in listOf(
                "/j/K7M2QX" to "standing.html", "/j/ABCD23" to "ordinary.html",
                "/j/AAAAAA" to "unknown.html", "/j/K7M2QX/calendar.ics" to "calendar.ics",
                "/j/K7M2QX/preview.png" to "standing-preview.png", "/j/ABCD23/preview.png" to "ordinary-preview.png",
            )) {
                val response = http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
                assertEquals(200, response.statusCode())
                File(directory, filename).writeBytes(response.body())
            }
            File(directory, "local-server-run.txt").writeText("Fetched via HTTP from local Netty fixture at $base.\nNo .env, database, accounts or external services used.\nServer stopped after evidence capture.\n")
        } finally {
            server.stop(0, 1000)
        }
    }
}

private class ShareTestProfiles(private val owner: UserId, private val name: String, private val now: Instant) : ProfileRepository {
    override suspend fun findById(userId: UserId): Profile? = Profile(userId, name, "", null, now, now).takeIf { userId == owner }
    override suspend fun findOrCreate(userId: UserId, signupPlatform: ClientContext.Platform): Profile = error("Public share routes must not create profiles")
    override suspend fun update(userId: UserId, displayName: String?, avatarEmoji: String?, avatarBackgroundColor: String?, clearAvatarBackgroundColor: Boolean): UpdateProfileOutcome = error("No writes expected")
    override suspend fun delete(userId: UserId) = error("No writes expected")
    override suspend fun touchInstallId(userId: UserId, installId: UUID): UUID? = error("No writes expected")
    override suspend fun findInstallSiblings(installId: UUID, currentUserId: UserId): List<UserId> = emptyList()
}
