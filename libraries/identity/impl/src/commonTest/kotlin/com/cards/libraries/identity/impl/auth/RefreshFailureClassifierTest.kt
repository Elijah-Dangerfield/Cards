package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import io.github.jan.supabase.exceptions.HttpRequestException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The classifier's load-bearing property is its **safety**: only a confirmed
 * auth rejection boots the user; everything ambiguous stays transient (keep the
 * session).
 */
class RefreshFailureClassifierTest : CoroutineTest() {

    @Test
    fun rejectedStatuses_bootTheSession() = runUnitTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            assertEquals(
                RefreshFailureKind.AuthRejected,
                classifyRefreshFailure(restException(status)),
                "$status is the auth server rejecting our token",
            )
        }
    }

    @Test
    fun otherRestStatuses_areTransient_neverBoot() = runUnitTest {
        // A 5xx or a rate limit says nothing about whether our token is valid.
        for (status in listOf(HttpStatusCode.InternalServerError, HttpStatusCode.TooManyRequests)) {
            assertEquals(RefreshFailureKind.Transient, classifyRefreshFailure(restException(status)))
        }
    }

    @Test
    fun networkError_isTransient_neverBoots() {
        val networkDown = HttpRequestException("connection refused", HttpRequestBuilder())
        assertEquals(RefreshFailureKind.Transient, classifyRefreshFailure(networkDown))
    }

    @Test
    fun unrecognizedError_isTransient_neverBoots() {
        // The conservative default: anything we don't positively recognize as a
        // server rejection keeps the session rather than destroying a guest's
        // (unrecoverable) progress on a maybe.
        assertEquals(RefreshFailureKind.Transient, classifyRefreshFailure(IllegalStateException("???")))
        assertEquals(RefreshFailureKind.Transient, classifyRefreshFailure(RuntimeException()))
    }
}
