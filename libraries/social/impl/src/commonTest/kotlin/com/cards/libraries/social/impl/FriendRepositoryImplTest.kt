package com.dangerfield.cards.libraries.social.impl

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.social.FriendProfile
import com.dangerfield.cards.libraries.social.RespondToRequestResult
import com.dangerfield.cards.libraries.social.SendFriendRequestResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.first
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

    @Test
    fun incoming_requests_resolve_and_preserve_server_order() = runUnitTest {
        val api = FakeSocialApi(
            incomingRequestIds = listOf("b", "a", "c"),
            profiles = listOf(publicProfile("a"), publicProfile("b"), publicProfile("c")),
        )
        val repo = FriendRepositoryImpl(api)

        repo.refreshIncomingRequests()

        assertEquals(listOf("b", "a", "c"), repo.observeIncomingRequests().first().map { it.id })
    }

    @Test
    fun incoming_requests_drop_ids_that_do_not_resolve() = runUnitTest {
        val api = FakeSocialApi(
            incomingRequestIds = listOf("a", "missing", "c"),
            profiles = listOf(publicProfile("a"), publicProfile("c")),
        )
        val repo = FriendRepositoryImpl(api)

        repo.refreshIncomingRequests()

        assertEquals(listOf("a", "c"), repo.observeIncomingRequests().first().map { it.id })
    }

    @Test
    fun empty_incoming_requests_yield_empty_without_resolving() = runUnitTest {
        val api = FakeSocialApi(incomingRequestIds = emptyList())
        val repo = FriendRepositoryImpl(api)

        repo.refreshIncomingRequests()

        assertEquals(emptyList<FriendProfile>(), repo.observeIncomingRequests().first())
        assertEquals(0, api.resolveCallCount)
    }

    @Test
    fun incoming_refresh_failure_keeps_last_good_value() = runUnitTest {
        val api = FakeSocialApi(
            incomingRequestIds = listOf("a"),
            profiles = listOf(publicProfile("a")),
        )
        val repo = FriendRepositoryImpl(api)
        repo.refreshIncomingRequests()

        api.incomingError = RuntimeException("network down")
        repo.refreshIncomingRequests()

        assertEquals(listOf("a"), repo.observeIncomingRequests().first().map { it.id })
    }

    @Test
    fun accept_drops_the_row_optimistically() = runUnitTest {
        val api = FakeSocialApi(
            incomingRequestIds = listOf("a", "b"),
            profiles = listOf(publicProfile("a"), publicProfile("b")),
        )
        val repo = FriendRepositoryImpl(api)
        repo.refreshIncomingRequests()

        assertEquals(RespondToRequestResult.Ok, repo.accept("a"))
        assertEquals("a", api.lastAcceptedUserId)
        assertEquals(listOf("b"), repo.observeIncomingRequests().first().map { it.id })
    }

    @Test
    fun decline_drops_the_row_optimistically() = runUnitTest {
        val api = FakeSocialApi(
            incomingRequestIds = listOf("a", "b"),
            profiles = listOf(publicProfile("a"), publicProfile("b")),
        )
        val repo = FriendRepositoryImpl(api)
        repo.refreshIncomingRequests()

        assertEquals(RespondToRequestResult.Ok, repo.decline("b"))
        assertEquals("b", api.lastDeclinedUserId)
        assertEquals(listOf("a"), repo.observeIncomingRequests().first().map { it.id })
    }

    @Test
    fun respond_treats_a_gone_request_as_success() = runUnitTest {
        val api = FakeSocialApi(
            incomingRequestIds = listOf("a", "b"),
            profiles = listOf(publicProfile("a"), publicProfile("b")),
            acceptResult = Result.failure(clientResponseException(HttpStatusCode.NotFound)),
        )
        val repo = FriendRepositoryImpl(api)
        repo.refreshIncomingRequests()

        assertEquals(RespondToRequestResult.Ok, repo.accept("a"))
        assertEquals(listOf("b"), repo.observeIncomingRequests().first().map { it.id })
    }

    @Test
    fun respond_keeps_the_row_on_network_failure() = runUnitTest {
        val boom = RuntimeException("offline")
        val api = FakeSocialApi(
            incomingRequestIds = listOf("a"),
            profiles = listOf(publicProfile("a")),
            declineResult = Result.failure(boom),
        )
        val repo = FriendRepositoryImpl(api)
        repo.refreshIncomingRequests()

        val result = repo.decline("a")
        assertIs<RespondToRequestResult.Error>(result)
        assertEquals(boom, result.cause)
        assertEquals(listOf("a"), repo.observeIncomingRequests().first().map { it.id })
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
