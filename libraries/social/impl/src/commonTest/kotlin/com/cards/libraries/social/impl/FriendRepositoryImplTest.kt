package com.dangerfield.cards.libraries.social.impl

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.social.SendFriendRequestResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FriendRepositoryImplTest : CoroutineTest() {

    @Test
    fun pending_request_maps_to_Requested() = runUnitTest {
        val api = FakeSocialApi(sendResult = Result.success(FriendRequestResultDto("requested")))
        val repo = FriendRepositoryImpl(api)

        assertEquals(SendFriendRequestResult.Requested, repo.sendRequest("u1"))
        assertEquals("u1", api.lastSentUserId)
    }

    @Test
    fun mutual_request_maps_to_Accepted() = runUnitTest {
        val api = FakeSocialApi(sendResult = Result.success(FriendRequestResultDto("accepted")))
        val repo = FriendRepositoryImpl(api)

        assertEquals(SendFriendRequestResult.Accepted, repo.sendRequest("u1"))
    }

    @Test
    fun forbidden_maps_to_NotPlayedWith() = runUnitTest {
        val api = FakeSocialApi(sendResult = Result.failure(clientResponseException(HttpStatusCode.Forbidden)))
        val repo = FriendRepositoryImpl(api)

        assertEquals(SendFriendRequestResult.NotPlayedWith, repo.sendRequest("u1"))
    }

    @Test
    fun bad_request_maps_to_SelfRequest() = runUnitTest {
        val api = FakeSocialApi(sendResult = Result.failure(clientResponseException(HttpStatusCode.BadRequest)))
        val repo = FriendRepositoryImpl(api)

        assertEquals(SendFriendRequestResult.SelfRequest, repo.sendRequest("u1"))
    }

    @Test
    fun too_many_requests_maps_to_RateLimited() = runUnitTest {
        val api = FakeSocialApi(sendResult = Result.failure(clientResponseException(HttpStatusCode.TooManyRequests)))
        val repo = FriendRepositoryImpl(api)

        assertEquals(SendFriendRequestResult.RateLimited, repo.sendRequest("u1"))
    }

    @Test
    fun network_failure_maps_to_Error() = runUnitTest {
        val boom = RuntimeException("offline")
        val api = FakeSocialApi(sendResult = Result.failure(boom))
        val repo = FriendRepositoryImpl(api)

        val result = repo.sendRequest("u1")
        assertIs<SendFriendRequestResult.Error>(result)
        assertEquals(boom, result.cause)
    }

    /**
     * Builds a real [ClientRequestException] for [status] by routing a request
     * through a [MockEngine] with `expectSuccess = true` — the same shape
     * production throws, so the status-based mapping is exercised honestly.
     */
    private suspend fun clientResponseException(status: HttpStatusCode): ClientRequestException {
        val mock = HttpClient(MockEngine { respond(content = ByteReadChannel("{}"), status = status) }) {
            expectSuccess = true
        }
        return try {
            mock.get("/")
            error("expected ClientRequestException for status $status")
        } catch (e: ClientRequestException) {
            e
        } finally {
            mock.close()
        }
    }
}
