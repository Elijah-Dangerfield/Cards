package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.AvatarPacks
import com.dangerfield.cards.server.domain.AvatarPalette
import com.dangerfield.cards.server.plugins.installSerialization
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvatarRoutesTest {

    @Test
    fun get_withoutAuthHeader_returns200() = runTest {
        get { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    @Test
    fun get_returnsEveryPackInTheRegistry() = runTest {
        get { resp ->
            val body = resp.body<AvatarPackResponse>()
            assertEquals(AvatarPacks.all.size, body.packs.size)
            val expectedIds = AvatarPacks.all.map { it.id }
            assertEquals(expectedIds, body.packs.map { it.id })
        }
    }

    @Test
    fun get_returnsBackgroundPaletteFromRegistry() = runTest {
        get { resp ->
            val body = resp.body<AvatarPackResponse>()
            assertEquals(AvatarPalette.values, body.backgroundPalette)
        }
    }

    @Test
    fun get_setsPublicMaxAgeSixtySeconds() = runTest {
        get { resp ->
            val cacheControl = resp.headers[HttpHeaders.CacheControl]
            assertNotNull(cacheControl, "missing Cache-Control header")
            assertTrue(cacheControl.contains("public"), "expected public visibility: $cacheControl")
            assertTrue(cacheControl.contains("max-age=60"), "expected max-age=60: $cacheControl")
        }
    }

    @Test
    fun get_premiumPacksCarryUnlockProductId_starterDoesNot() = runTest {
        get { resp ->
            val body = resp.body<AvatarPackResponse>()
            val starter = body.packs.single { it.id == AvatarPacks.Starter.id }
            assertNull(starter.unlockProductId)

            val premium = body.packs.filter { it.id != AvatarPacks.Starter.id }
            assertTrue(premium.isNotEmpty(), "expected at least one premium pack in registry")
            premium.forEach { pack ->
                assertNotNull(pack.unlockProductId, "premium pack ${pack.id} should carry unlockProductId")
            }
        }
    }

    private suspend fun get(assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit) {
        testApplication {
            application {
                installSerialization()
                routing { avatarRoutes() }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val response = client.get("/v1/avatars")
            assert(response)
        }
    }
}
