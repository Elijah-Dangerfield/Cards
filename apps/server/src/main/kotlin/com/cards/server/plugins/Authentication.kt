package com.dangerfield.cards.server.plugins

import com.auth0.jwk.JwkProviderBuilder
import com.dangerfield.cards.server.config.SupabaseConfig
import com.dangerfield.cards.server.domain.UserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import java.net.URL
import java.util.concurrent.TimeUnit

/** Authentication-realm name; used by `authenticate(SUPABASE_JWT_AUTH)` in routes. */
const val SUPABASE_JWT_AUTH = "supabase-jwt"

/**
 * Wires verification of Supabase-issued JWTs.
 *
 * Supabase signs every JWT it issues (anonymous and OAuth) with ES256
 * (asymmetric). Our server fetches the project's public signing keys
 * from the JWKS endpoint and verifies inbound tokens against them.
 *
 * Routes wrapped in `authenticate(SUPABASE_JWT_AUTH) { … }` reject calls
 * without a valid Authorization header. Handler code reads the caller's
 * id via `call.userId()`.
 *
 * V1 verifies signature + issuer + audience + exp/iat. We don't validate
 * the `is_anonymous` flag here because both anon and claimed users get
 * the same access (we differentiate downstream when needed — e.g.
 * leaderboards exclude anon users).
 *
 * Cache config: 10 keys × 24h reflects Supabase's slow rotation cadence.
 * Rate-limit (10 requests / minute) is the failsafe — if a flood of
 * tokens reference unknown keys, we don't DoS the JWKS endpoint trying
 * to look them all up.
 */
fun Application.installAuthentication(config: SupabaseConfig) {
    val jwkProvider = JwkProviderBuilder(URL(config.jwksUrl))
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    install(Authentication) {
        jwt(SUPABASE_JWT_AUTH) {
            verifier(jwkProvider, config.expectedIssuer) {
                withAudience("authenticated")
            }
            validateAndChallengeForUserId()
        }
    }
}

/**
 * Test-friendly entry point. Production tests want to mint JWTs they
 * control (HS256 with a known secret) rather than hitting the real
 * Supabase JWKS endpoint. They pass a pre-built [JWTVerifier] in.
 */
internal fun Application.installAuthenticationWithVerifier(verifier: com.auth0.jwt.interfaces.JWTVerifier) {
    install(Authentication) {
        jwt(SUPABASE_JWT_AUTH) {
            verifier(verifier)
            validateAndChallengeForUserId()
        }
    }
}

private fun io.ktor.server.auth.jwt.JWTAuthenticationProvider.Config.validateAndChallengeForUserId() {
    validate { credential ->
        // sub must parse as a UUID. Anything else is a malformed token.
        try {
            val sub = credential.payload.subject
            if (sub.isNullOrBlank()) null
            else {
                UserId.parse(sub) // throws on invalid UUID
                JWTPrincipal(credential.payload)
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    challenge { _, _ ->
        call.respond(
            HttpStatusCode.Unauthorized,
            mapOf("error" to mapOf("code" to "unauthorized", "message" to "Missing or invalid access token")),
        )
    }
}

/**
 * Resolves the caller's `auth.users.id` from the validated JWT. Only safe
 * to call inside an `authenticate(SUPABASE_JWT_AUTH) { … }` block —
 * outside it returns null.
 */
fun ApplicationCall.userId(): UserId? {
    val principal = principal<JWTPrincipal>() ?: return null
    val sub = principal.payload.subject ?: return null
    return UserId.parse(sub)
}

/**
 * Whether the caller's JWT was issued for an anonymous user. Supabase
 * marks anonymous users with the `is_anonymous: true` claim. Useful for
 * gating features like leaderboards or claim-only chip top-ups.
 */
fun ApplicationCall.isAnonymousUser(): Boolean {
    val principal = principal<JWTPrincipal>() ?: return false
    return principal.payload.getClaim("is_anonymous").asBoolean() ?: false
}
