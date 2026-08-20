package com.dangerfield.cards.server.domain

/**
 * The caller presented a JWT we verified, but [userId] has no row in Supabase
 * `auth.users`. Two ways to get here: the account was deleted mid-session (the
 * V11 `ON DELETE CASCADE` took its profile/wallet/inventory with it), or the
 * token was minted against a different Supabase project than the one this
 * server's database belongs to.
 *
 * Either way the session is unrecoverable and every per-user write is doomed on
 * the `*_user_id_fk` constraint, so the only useful answer is "re-authenticate".
 * [com.dangerfield.cards.server.plugins.installStatusPages] renders it as a
 * `401` carrying [WIRE_CODE]; the client reads that code and tears the session
 * down instead of retrying.
 *
 * The message deliberately names no constraint or table — a raw FK error in a
 * response body tells an unauthenticated caller more about our schema than they
 * need to know.
 */
class UnknownAuthUserException(val userId: UserId) : RuntimeException(
    "No auth.users row for the authenticated caller",
) {
    companion object {
        /** Machine-readable `error.code` the client routes on. */
        const val WIRE_CODE = "account_not_found"
    }
}
