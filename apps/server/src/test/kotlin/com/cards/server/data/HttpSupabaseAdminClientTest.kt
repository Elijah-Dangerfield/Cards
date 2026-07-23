package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.config.SupabaseConfig
import com.dangerfield.cards.server.domain.DeleteUserResult
import com.dangerfield.cards.server.domain.UpdateDisplayNameResult
import com.dangerfield.cards.server.domain.UserId
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpSupabaseAdminClientTest {

    private val projectUrl = "https://test-project.supabase.co"
    private val serviceRoleKey = "test-service-role-key"
    private val userId = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))

    @Test
    fun deleteUser_returnsSuccess_on204() = runTest {
        val engine = MockEngine { req ->
            assertEquals(HttpMethod.Delete, req.method)
            assertEquals("/auth/v1/admin/users/${userId.value}", req.url.encodedPath)
            assertEquals("Bearer $serviceRoleKey", req.headers[HttpHeaders.Authorization])
            assertEquals(serviceRoleKey, req.headers["apikey"])
            val body = (req.body as TextContent).text
            assertTrue(
                body.contains("\"should_soft_delete\"") && body.contains("false"),
                "DELETE must request a hard delete (should_soft_delete=false); was: $body",
            )
            respond(content = "", status = HttpStatusCode.NoContent, headers = headersOf())
        }
        val client = HttpSupabaseAdminClient(
            config = SupabaseConfig(projectUrl, serviceRoleKey),
            engine = engine,
        )
        val result = client.deleteUser(userId)
        assertEquals(DeleteUserResult.Success, result)
    }

    @Test
    fun deleteUser_returnsAlreadyGone_on404() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
        val client = HttpSupabaseAdminClient(
            config = SupabaseConfig(projectUrl, serviceRoleKey),
            engine = engine,
        )
        assertEquals(DeleteUserResult.AlreadyGone, client.deleteUser(userId))
    }

    @Test
    fun deleteUser_returnsFailure_on500() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        val client = HttpSupabaseAdminClient(
            config = SupabaseConfig(projectUrl, serviceRoleKey),
            engine = engine,
        )
        val result = client.deleteUser(userId)
        val failure = assertIs<DeleteUserResult.Failure>(result)
        assertEquals(500, failure.statusCode)
    }

    @Test
    fun updateUserDisplayName_putsMetadata_on200() = runTest {
        val engine = MockEngine { req ->
            assertEquals(HttpMethod.Put, req.method)
            assertEquals("/auth/v1/admin/users/${userId.value}", req.url.encodedPath)
            assertEquals("Bearer $serviceRoleKey", req.headers[HttpHeaders.Authorization])
            assertEquals(serviceRoleKey, req.headers["apikey"])
            val body = (req.body as TextContent).text
            assertTrue(
                body.contains("\"user_metadata\"") && body.contains("\"display_name\":\"brave-fox-101\""),
                "PUT must carry user_metadata.display_name; was: $body",
            )
            respond(content = "{}", status = HttpStatusCode.OK, headers = headersOf())
        }
        val client = HttpSupabaseAdminClient(
            config = SupabaseConfig(projectUrl, serviceRoleKey),
            engine = engine,
        )
        assertEquals(
            UpdateDisplayNameResult.Success,
            client.updateUserDisplayName(userId, "brave-fox-101"),
        )
    }

    @Test
    fun updateUserDisplayName_returnsFailure_on500() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        val client = HttpSupabaseAdminClient(
            config = SupabaseConfig(projectUrl, serviceRoleKey),
            engine = engine,
        )
        val failure = assertIs<UpdateDisplayNameResult.Failure>(
            client.updateUserDisplayName(userId, "brave-fox-101"),
        )
        assertEquals(500, failure.statusCode)
    }

    @Test
    fun updateUserDisplayName_shortCircuitsNotConfigured_whenServiceRoleKeyAbsent() = runTest {
        val engine = MockEngine { error("must not be called when service role key is missing") }
        val client = HttpSupabaseAdminClient(
            config = SupabaseConfig(projectUrl, serviceRoleKey = null),
            engine = engine,
        )
        assertEquals(
            UpdateDisplayNameResult.NotConfigured,
            client.updateUserDisplayName(userId, "brave-fox-101"),
        )
    }

    @Test
    fun deleteUser_shortCircuitsNotConfigured_whenServiceRoleKeyAbsent() = runTest {
        val engine = MockEngine { error("must not be called when service role key is missing") }
        val client = HttpSupabaseAdminClient(
            config = SupabaseConfig(projectUrl, serviceRoleKey = null),
            engine = engine,
        )
        assertEquals(DeleteUserResult.NotConfigured, client.deleteUser(userId))
    }

    @Test
    fun deleteUser_shortCircuitsNotConfigured_whenServiceRoleKeyIsBlank() = runTest {
        val engine = MockEngine { error("must not be called when service role key is blank") }
        val client = HttpSupabaseAdminClient(
            config = SupabaseConfig(projectUrl, serviceRoleKey = "  "),
            engine = engine,
        )
        assertEquals(DeleteUserResult.NotConfigured, client.deleteUser(userId))
    }

    @Test
    fun deleteUser_wrapsTransportError_asFailure() = runTest {
        val engine = MockEngine { throw java.io.IOException("connection refused") }
        val client = HttpSupabaseAdminClient(
            config = SupabaseConfig(projectUrl, serviceRoleKey),
            engine = engine,
        )
        val result = client.deleteUser(userId)
        val failure = assertIs<DeleteUserResult.Failure>(result)
        assertTrue(failure.cause is java.io.IOException)
    }
}
