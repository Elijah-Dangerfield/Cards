package com.dangerfield.cards.server.domain

/**
 * Wrapper for the Supabase Admin REST API — the surface that requires the
 * project's `service_role` JWT instead of a user-issued one.
 *
 * V1 only needs user deletion (App Store and Play Store both require an
 * in-app "delete my account" path). Add new methods here as needs grow;
 * keep them sealed-outcome-typed so callers can branch on misconfiguration
 * vs. upstream failure without try/catch ceremony.
 *
 * Implementations talk to `<projectUrl>/auth/v1/admin/...` with the
 * service-role bearer + apikey headers Supabase requires. Tests bind a
 * fake — see `MeRoutesTest.FakeSupabaseAdminClient`.
 */
interface SupabaseAdminClient {
    suspend fun deleteUser(userId: UserId): DeleteUserResult
}

sealed interface DeleteUserResult {
    /** Supabase confirmed the user is gone (204 No Content). */
    data object Success : DeleteUserResult
    /** Supabase reported no such user (404) — treat as success from caller's POV. */
    data object AlreadyGone : DeleteUserResult
    /** `SUPABASE_SERVICE_ROLE_KEY` not set on this server — the route maps to 503. */
    data object NotConfigured : DeleteUserResult
    /** Upstream returned a non-success status, or the call failed transport-side. */
    data class Failure(val statusCode: Int?, val cause: Throwable?) : DeleteUserResult
}
