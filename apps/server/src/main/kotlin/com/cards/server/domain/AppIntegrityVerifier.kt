package com.dangerfield.cards.server.domain

/**
 * Verifies that an inbound request comes from a genuine, untampered build
 * of the Cards client. Google calls this "Play Integrity," Apple calls it
 * "App Attest"; both produce a short-lived token the client must mint and
 * pass to us, and both have server-side verification paths.
 *
 * **V1 status: NOT enforced.** A NoOpAppIntegrityVerifier is bound by
 * default — every request passes. The infrastructure is in place so that
 * adding the real verifiers is a binding swap + a route guard, not a
 * cross-cutting refactor.
 *
 * Why the NoOp default is acceptable for V1:
 *  - Anonymous Supabase users have no PII, no real money, no leaderboard
 *    impact. The worst case is a scripted attacker minting throwaway
 *    profiles. The orphan sweep cleans those up after 30 days; the rate
 *    limiter throttles the rate they can be created.
 *  - Real Play Integrity / App Attest verification needs Google Play
 *    Console + Apple Developer setup that's outside the dev environment.
 *    Wiring it now without those credentials would mean turning it off
 *    everywhere or stubbing the verifier — same effect, more friction.
 *
 * When to enforce: before the first invited-real-users release. Bind real
 * verifiers (PlayIntegrityVerifier + AppAttestVerifier), gate the
 * sensitive routes with `requireVerifiedIntegrity(verifier)`, and
 * require the client to attach `X-App-Integrity-Token` on the
 * corresponding requests.
 *
 * Where to enforce: at minimum on `/v1/me` first-touch (the get-or-create
 * profile path), since that's where the Supabase anon JWT becomes
 * load-bearing for us. Future surfaces (room creation, MP join, chip
 * grants) follow the same pattern.
 *
 * See decisions.md "App integrity attestation — planned" for the full
 * rollout plan.
 */
interface AppIntegrityVerifier {
    suspend fun verify(token: String?, deviceFingerprint: AppIntegrityFingerprint): AppIntegrityResult
}

data class AppIntegrityFingerprint(
    /** Best-effort platform label from the client: "android" / "ios" / null. */
    val platform: String?,
    /** Best-effort client version (semver string). */
    val clientVersion: String?,
)

sealed interface AppIntegrityResult {
    data object Verified : AppIntegrityResult
    /** Token was missing entirely. */
    data object Missing : AppIntegrityResult
    /** Token was present but didn't validate (signature, expiry, package mismatch, etc). */
    data class Invalid(val reason: String) : AppIntegrityResult
    /** Upstream verification service (Google / Apple) was unreachable. */
    data class TransientFailure(val cause: Throwable?) : AppIntegrityResult
    /** Verifier isn't configured (e.g. NoOp default in V1) — caller treats as Verified by policy. */
    data object NotConfigured : AppIntegrityResult
}
