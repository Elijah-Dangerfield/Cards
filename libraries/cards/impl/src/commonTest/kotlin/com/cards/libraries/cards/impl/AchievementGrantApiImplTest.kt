package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the wire contract of [AchievementGrantApiImpl] against the
 * server's `POST /v1/me/grants/achievement/{achievementId}` route.
 *
 * What's pinned:
 *  - URL is built from `AchievementId.name`.
 *  - 200 OK returns true (grant landed → caller should sync inventory).
 *  - 204 No Content returns false (no reward / unknown id).
 *  - 5xx returns false (the impl swallows errors; idempotent retry has
 *    already happened by the time we get back the Catching).
 *  - Underlying HTTP method is POST (not PUT/GET — the route is non-
 *    GET-safe even though it's idempotent at the storage layer).
 */
class AchievementGrantApiImplTest : CoroutineTest() {

    @Test
    fun grant_returnsTrue_whenServerRespondsOk() = runUnitTest {
        val api = buildApi { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(
                "/v1/me/grants/achievement/POT_5000",
                request.url.encodedPath,
            )
            respondJson("""{"productId":"title_pot_magnet","costChipsAtPurchase":0,"purchasedAtEpochMs":1700000000000,"acquisitionSource":"earned"}""")
        }
        assertTrue(api.grantAchievement(AchievementId.POT_5000))
    }

    @Test
    fun grant_returnsFalse_whenServerRespondsNoContent() = runUnitTest {
        val api = buildApi { _ -> respond(content = "", status = HttpStatusCode.NoContent) }
        assertFalse(api.grantAchievement(AchievementId.FIRST_HAND))
    }

    @Test
    fun grant_returnsFalse_andSwallowsError_whenServerErrors() = runUnitTest {
        // 500 throws inside the authedCall block (expectSuccess = true on
        // the production client). The impl wraps in Catching and falls
        // back to false — the achievement-recording flow must never break
        // because the grant POST failed.
        val api = buildApi { _ -> respondJson("""{}""", status = HttpStatusCode.InternalServerError) }
        assertFalse(api.grantAchievement(AchievementId.POT_5000))
    }

    @Test
    fun grant_buildsUrlFromAchievementIdName() = runUnitTest {
        // Uses each id's enum name verbatim; matches the server's path
        // lookup against AchievementRewards's string keys.
        val seen = mutableListOf<String>()
        val api = buildApi { request ->
            seen += request.url.encodedPath
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        api.grantAchievement(AchievementId.COMEBACK_FROM_5BB)
        api.grantAchievement(AchievementId.HANDS_100)
        assertEquals(
            listOf(
                "/v1/me/grants/achievement/COMEBACK_FROM_5BB",
                "/v1/me/grants/achievement/HANDS_100",
            ),
            seen,
        )
    }

    // ---------- scaffolding ----------

    private fun buildApi(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): AchievementGrantApiImpl {
        val mockEngine = MockEngine(handler)
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        coerceInputValues = true
                    },
                )
            }
            expectSuccess = true
        }
        @OptIn(com.dangerfield.cards.libraries.networking.InternalNetworkingApi::class)
        val networkClient = object : NetworkClient {
            override val client: HttpClient = client
            override val authenticatedClient: HttpClient = client
            override suspend fun awaitAuthReady() = Unit
        }
        return AchievementGrantApiImpl(networkClient)
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
    )
}
