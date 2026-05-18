package com.dangerfield.cards.server.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed table definitions that mirror `src/main/resources/db/migration/V1__profiles.sql`.
 *
 * **Flyway is the source of truth for the schema.** These definitions are
 * read-side projections — Exposed uses them to type-check our queries and
 * map rows to Kotlin values. The smoke test in `DatabaseSchemaTest` boots
 * Flyway against a Testcontainer Postgres and fails if the two get out of
 * sync.
 *
 * Auth-related tables (`auth.users`, refresh tokens, identities) live in
 * Supabase's `auth` schema and are managed by Supabase Auth, not us. We
 * never define them here — we only reference `auth.users(id)` by storing
 * UUIDs that match those rows. The link is enforced at the application
 * layer (we only insert profiles for users we've authenticated via a
 * valid Supabase JWT).
 */
object ProfilesTable : Table("profiles") {
    val userId = uuid("user_id")
    val displayName = text("display_name").uniqueIndex("profiles_display_name_uq")
    val avatarEmoji = text("avatar_emoji")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId)
}
