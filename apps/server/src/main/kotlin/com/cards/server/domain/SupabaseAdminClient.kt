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

    /**
     * Enumerate anonymous Supabase users whose last sign-in is older than
     * [olderThan]. Paginates internally until the upstream list is
     * exhausted; returns an empty list when the service-role key isn't
     * configured (callers should check ahead of time and skip the sweep).
     *
     * The full result lives in memory by design — V1 is small enough that
     * a single sweep returns <10k users in the worst case. If we grow past
     * that, stream via a Flow and run the delete loop on each emission.
     */
    suspend fun listAnonymousUsersOlderThan(olderThan: kotlin.time.Instant): List<UserId>

    /**
     * Mirror the app's display name into `auth.users.user_metadata.display_name`
     * so the Supabase Studio Users table shows who each auth row is at a glance.
     * Vanity metadata only — the `profiles` table stays the source of truth,
     * and callers must never fail their own request on a failed mirror.
     */
    suspend fun updateUserDisplayName(userId: UserId, displayName: String): UpdateDisplayNameResult
}

sealed interface UpdateDisplayNameResult {
    /** Supabase accepted the metadata update. */
    data object Success : UpdateDisplayNameResult
    /** `SUPABASE_SERVICE_ROLE_KEY` not set on this server — mirroring is off. */
    data object NotConfigured : UpdateDisplayNameResult
    /** Upstream returned a non-success status, or the call failed transport-side. */
    data class Failure(val statusCode: Int?, val cause: Throwable?) : UpdateDisplayNameResult
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
