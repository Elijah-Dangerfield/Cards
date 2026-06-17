package com.dangerfield.cards.libraries.identity.impl.auth

import io.github.jan.supabase.exceptions.HttpRequestException
import io.ktor.client.request.HttpRequestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The classifier's load-bearing property is its **safety**: only a confirmed
 * auth rejection boots the user; everything ambiguous stays transient (keep the
 * session). A 401/403 [io.github.jan.supabase.exceptions.RestException] maps to
 * [RefreshFailureKind.AuthRejected] in production — that branch mirrors the
 * already-shipped mapping in `SupabaseAuthRepositoryImpl.refreshSession` and
 * can't be synthesized cheaply here (it needs a real Ktor `HttpResponse`), so
 * these tests pin the transient branches that protect a live session.
 */
class RefreshFailureClassifierTest {

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
