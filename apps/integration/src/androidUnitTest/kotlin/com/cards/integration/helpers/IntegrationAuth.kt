package com.cards.integration.helpers

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.plugins.JwtVerification
import java.util.Date

/**
 * Test-controlled HS256 auth: mints Supabase-shaped JWTs and the matching
 * [JwtVerification.Static] strategy the harness server installs via the server's
 * real `installAuthentication` seam (so the harness exercises the production
 * validate/challenge path rather than re-implementing it).
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

    /** The verification strategy that mirrors [mintJwt] — fed to the real install. */
    val verification: JwtVerification = JwtVerification.Static(
        JWT.require(Algorithm.HMAC256(SECRET))
            .withIssuer(ISSUER)
            .withAudience("authenticated")
            .build(),
    )
}
