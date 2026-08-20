package com.dangerfield.cards.server.db

import org.postgresql.util.PSQLException

/**
 * True when [this] (or anything in its cause chain) is a Postgres foreign-key
 * violation against one of V11's `<table>_user_id_fk` constraints — the schema's
 * way of saying the `user_id` we tried to write has no `auth.users` row.
 *
 * The constraint name is read off [PSQLException.getServerErrorMessage] where the
 * driver parses it into a field, and only falls back to substring-matching the
 * message when it doesn't. Suffix-matching `_user_id_fk` is exact here: the only
 * other foreign keys in the schema are `room_members.room_code` and
 * `app_config_targeting.flag_path`, neither of which can match.
 *
 * This is the safety net behind the explicit `auth.users` pre-flight in the
 * profile-create path. The pre-flight is what answers on the hot path
 * (`GET /v1/me`); this catches any other per-user write that reaches a child
 * table without one.
 */
fun Throwable.violatesUserIdForeignKey(): Boolean =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }
        .filterIsInstance<PSQLException>()
        .any { it.isUserIdForeignKeyViolation() }

private fun PSQLException.isUserIdForeignKeyViolation(): Boolean {
    if (sqlState != POSTGRES_FOREIGN_KEY_VIOLATION_SQLSTATE) return false
    val constraint = serverErrorMessage?.constraint
    return constraint?.endsWith(USER_ID_FK_SUFFIX)
        ?: (message?.contains(USER_ID_FK_SUFFIX) == true)
}

private const val POSTGRES_FOREIGN_KEY_VIOLATION_SQLSTATE = "23503"
private const val USER_ID_FK_SUFFIX = "_user_id_fk"
