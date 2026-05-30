-- Minimal stub of Supabase's auth schema for Testcontainers.
--
-- In production this schema is owned and migrated by Supabase Auth (GoTrue).
-- Our app tables (public.profiles, wallets, inventory, equipment,
-- user_messages) reference auth.users(id) via FK. The Testcontainer
-- Postgres ships with neither, so this script — run by Testcontainers
-- before Flyway — creates just enough of the auth schema to satisfy the
-- FKs.
--
-- We only need auth.users(id UUID PRIMARY KEY). The real table has dozens
-- of columns (email, encrypted_password, etc.) that we don't reference,
-- so we don't bother stubbing them. If a future migration adds an FK on
-- some other column from auth.users, add it here.
--
-- Test code that creates app rows is responsible for first inserting a
-- matching auth.users row — see DatabaseTest.seedAuthUser().
CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE IF NOT EXISTS auth.users (
    id UUID PRIMARY KEY,
    -- Mirrors the real Supabase column. The L1 install_id sweep queries
    -- `auth.users.is_anonymous` directly via a SQL `IN (SELECT id FROM
    -- auth.users WHERE is_anonymous = TRUE)` gate, so the stub has to
    -- carry it. Defaults to FALSE so existing seed callers stay
    -- non-anonymous; callers explicitly opt in via seedAuthUser(..., isAnonymous = true).
    is_anonymous BOOLEAN NOT NULL DEFAULT FALSE
);
