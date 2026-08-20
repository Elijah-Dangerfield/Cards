package com.dangerfield.cards.server.db

import org.postgresql.util.PSQLException

/**
 * True when [this] (or anything in its cause chain) is a Postgres foreign-key
 * violation against one of V11's `<table>_user_id_fk` constraints — the schema's
 * way of saying the `user_id` we tried to write has no `auth.users` row.
 *
 * The constraint name is read off [PSQLException.getServerErrorMessage] where the
 * driver parses it into a field, and only falls back to matching the message
 * when it doesn't.
 *
 * Matching the suffix rather than any `auth.users` reference is deliberate. V11
 * named the constraints on the columns holding the *row owner's* id
 * (`profiles`, `wallets`, `wallet_events`, `inventory`, `equipment`,
 * `user_messages`); the columns that point at somebody else — `reports`'
 * reporter/reported pair, `friendships.user_a`/`user_b`, an opponent id — kept
 * Postgres' default `_fkey` names. Only the first group says anything about the
 * caller's own account.
 *
 * This is the safety net behind the explicit `auth.users` pre-flight in the
 * profile-create path. The pre-flight is what answers on the hot path
 * (`GET /v1/me`); this catches any other per-user write that reaches a child
 * table without one. Whether the *caller* is the missing user is the caller's
 * question to settle — see `respondAccountNotFound`.
 */
fun Throwable.violatesUserIdForeignKey(): Boolean =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }
        .filterIsInstance<PSQLException>()
        .any { it.isUserIdForeignKeyViolation() }

private fun PSQLException.isUserIdForeignKeyViolation(): Boolean {
    if (sqlState != POSTGRES_FOREIGN_KEY_VIOLATION_SQLSTATE) return false
    val constraint = serverErrorMessage?.constraint
    return constraint?.endsWith(USER_ID_FK_SUFFIX)
        ?: (message?.contains(USER_ID_FK_NAME) == true)
}

private const val POSTGRES_FOREIGN_KEY_VIOLATION_SQLSTATE = "23503"
private const val USER_ID_FK_SUFFIX = "_user_id_fk"

/**
 * The suffix as it appears inside a message, ending at a name boundary. Plain
 * `contains` would also match `_user_id_fkey`, which is what Postgres names the
 * default constraints on the columns pointing at *another* user
 * (`reports.reported_user_id`, `friendships.user_a`).
 */
private val USER_ID_FK_NAME = Regex("${Regex.escape(USER_ID_FK_SUFFIX)}(?![A-Za-z0-9_])")
