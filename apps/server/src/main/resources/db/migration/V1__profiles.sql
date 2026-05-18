-- V1: Profiles schema.
--
-- Auth identities live in Supabase's `auth.users` table, which Supabase
-- manages. We don't touch that table — we just reference its rows by
-- their UUID primary key from our own `profiles` table.
--
-- No FOREIGN KEY constraint to auth.users(id): Supabase owns that table's
-- lifecycle, and Testcontainers (which we use for integration tests)
-- doesn't have an `auth` schema. The application layer enforces the link:
-- profile rows are only inserted by code that's already verified a
-- Supabase JWT, which guarantees the user_id is real.
--
-- All timestamps are TIMESTAMPTZ stored in UTC. The Ktor server only ever
-- writes UTC instants; clients render in their local zone.

-- --------------------------------------------------------------------------
-- profiles
--
-- Per-user public profile. Created lazily by `/v1/me` on first contact
-- (when we see a new Supabase user_id), using server-generated random
-- name + emoji. `display_name` is globally unique because we reference
-- players by name in room and leaderboard UI.
-- --------------------------------------------------------------------------
CREATE TABLE profiles (
    user_id        UUID PRIMARY KEY,
    display_name   TEXT NOT NULL,
    avatar_emoji   TEXT NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT profiles_display_name_uq UNIQUE (display_name)
);
