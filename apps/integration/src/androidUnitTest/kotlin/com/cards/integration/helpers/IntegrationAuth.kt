package com.cards.integration.helpers

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import java.util.Date
import java.util.UUID

/**
 * Test-controlled HS256 auth that mirrors the server's Supabase JWT verifier
 * shape (issuer + `authenticated` audience + UUID `sub`).
 *
 * Production installs [com.dangerfield.cards.server.plugins.installAuthentication],
 * which verifies against Supabase's JWKS. The server's own verifier-based test
 * install is `internal`, so we re-implement the matching install here against a
 * known secret — the one place the harness mints and trusts its own tokens.
 */
object IntegrationAuth {
    const val ISSUER = "https://integration-test.supabase.co/auth/v1"
    private const val SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef"

    /** A signed, one-hour Supabase-shaped JWT for [userId] (a UUID string). */
    fun mintJwt(userId: String): String = JWT.create()
        .withIssuer(ISSUER)
        .withAudience("authenticated")
        .withSubject(userId)
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000))
        .sign(Algorithm.HMAC256(SECRET))

    /** Installs the verifier that mirrors [mintJwt], under the real auth realm. */
    fun Application.installHarnessAuth() {
        install(Authentication) {
            jwt(SUPABASE_JWT_AUTH) {
                verifier(
                    JWT.require(Algorithm.HMAC256(SECRET))
                        .withIssuer(ISSUER)
                        .withAudience("authenticated")
                        .build(),
                )
                validate { credential ->
                    val sub = credential.payload.subject
                    if (sub.isNullOrBlank()) {
                        null
                    } else {
                        UUID.fromString(sub) // mirrors the server's UserId.parse(sub)
                        JWTPrincipal(credential.payload)
                    }
                }
                challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized) }
            }
        }
    }
}
