package com.dangerfield.cards.server.plugins

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.interfaces.JWTVerifier
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
/**
 * How inbound JWTs are verified — the seam that makes auth testable.
 *
 *  - [Jwks] is production: fetch Supabase's public ES256 keys from the JWKS
 *    endpoint and verify signatures against them (a network hit when the
 *    provider first resolves a key).
 *  - [Static] is for tests + the integration harness: verify against a
 *    caller-supplied verifier (e.g. HS256 + a known secret). No startup
 *    network, and the caller can mint matching tokens.
 *
 * Exposed (not `internal`) so cross-module integration tests can boot the real
 * auth install without re-implementing it.
 */
sealed interface JwtVerification {
    data class Jwks(val jwksUrl: String, val issuer: String) : JwtVerification
    data class Static(val verifier: JWTVerifier) : JwtVerification
}

/**
 * Installs JWT auth under [SUPABASE_JWT_AUTH] using the given [verification]
 * strategy. The single seam shared by production and tests; the validate +
 * challenge behaviour (UUID `sub`, JSON 401) is identical regardless of how
 * signatures are checked.
 */
fun Application.installAuthentication(verification: JwtVerification) {
    install(Authentication) {
        jwt(SUPABASE_JWT_AUTH) {
            when (verification) {
                is JwtVerification.Jwks -> {
                    val jwkProvider = JwkProviderBuilder(URL(verification.jwksUrl))
                        .cached(10, 24, TimeUnit.HOURS)
                        .rateLimited(10, 1, TimeUnit.MINUTES)
                        .build()
                    verifier(jwkProvider, verification.issuer) {
                        withAudience("authenticated")
                    }
                }

                is JwtVerification.Static -> verifier(verification.verifier)
            }
            validateAndChallengeForUserId()
        }
    }
}

/** Production convenience: verify against Supabase's JWKS endpoint. */
fun Application.installAuthentication(config: SupabaseConfig) =
    installAuthentication(JwtVerification.Jwks(config.jwksUrl, config.expectedIssuer))

/**
 * Kept for existing in-module test call sites; delegates to the [Static] seam.
 * New code (incl. cross-module tests) should call [installAuthentication] with
 * [JwtVerification.Static] directly.
 */
internal fun Application.installAuthenticationWithVerifier(verifier: JWTVerifier) =
    installAuthentication(JwtVerification.Static(verifier))

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
