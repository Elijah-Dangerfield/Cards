package com.dangerfield.cards.libraries.social.impl

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.social.ReportPlayerResult
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

class ReportRepositoryImplTest : CoroutineTest() {

    @Test
    fun success_maps_to_Reported_andForwardsContext() = runUnitTest {
        val api = FakeSocialApi()
        val repo = ReportRepositoryImpl(api)

        assertEquals(
            ReportPlayerResult.Reported,
            repo.reportPlayer("u1", roomCode = "ABCD", reason = null),
        )
        assertEquals(Triple("u1", "ABCD", null), api.lastReport)
    }

    @Test
    fun forwards_reasonCategories_toTheApi() = runUnitTest {
        val api = FakeSocialApi()
        val repo = ReportRepositoryImpl(api)

        repo.reportPlayer("u1", roomCode = "ABCD", reason = "stalling", categories = listOf("cheating", "harassment"))

        assertEquals(listOf("cheating", "harassment"), api.lastReportCategories)
        assertEquals(Triple("u1", "ABCD", "stalling"), api.lastReport)
    }

    @Test
    fun too_many_requests_maps_to_RateLimited() = runUnitTest {
        val api = FakeSocialApi(
            reportResult = Result.failure(clientResponseException(HttpStatusCode.TooManyRequests)),
        )
        val repo = ReportRepositoryImpl(api)

        assertEquals(ReportPlayerResult.RateLimited, repo.reportPlayer("u1", null, null))
    }

    @Test
    fun other_client_error_maps_to_Error() = runUnitTest {
        val api = FakeSocialApi(
            reportResult = Result.failure(clientResponseException(HttpStatusCode.BadRequest)),
        )
        val repo = ReportRepositoryImpl(api)

        assertIs<ReportPlayerResult.Error>(repo.reportPlayer("u1", null, null))
    }

    @Test
    fun network_failure_maps_to_Error() = runUnitTest {
        val boom = RuntimeException("offline")
        val api = FakeSocialApi(reportResult = Result.failure(boom))
        val repo = ReportRepositoryImpl(api)

        val result = repo.reportPlayer("u1", null, null)
        assertIs<ReportPlayerResult.Error>(result)
        assertEquals(boom, result.cause)
    }

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
