package com.dangerfield.cards.libraries.identity.impl.auth

import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel

/**
 * A real supabase-kt [RestException] carrying [status].
 *
 * `RestException` reads its `statusCode` off a live Ktor [HttpResponse], so it
 * can't be hand-rolled — the auth layer's two most destructive decisions (boot
 * the session vs keep it, [classifyRefreshFailure] and the repository's
 * refresh mapping) branch on that status, and pinning them needs the real
 * shape. A [MockEngine] round trip is the cheapest way to get one.
 */
internal suspend fun restException(status: HttpStatusCode): RestException {
    val client = HttpClient(MockEngine { respond(content = ByteReadChannel("{}"), status = status) })
    return try {
        RestException(
            error = "synthesized ${status.value}",
            description = null,
            response = client.get("/"),
        )
    } finally {
        client.close()
    }
}
