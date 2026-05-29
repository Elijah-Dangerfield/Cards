-- V49: Per-install identifier on profiles.
--
-- Implements §6.1 of the product spec + the L1 orphan-cleanup design in
-- docs/recovery-and-orphaned-accounts.md.
--
-- Anonymous-by-default users get a server-side profile keyed to their
-- Supabase user_id. The client generates a UUID once at first launch and
-- sends it on every authenticated request as `X-Install-Id`. The /v1/me
-- handler tags the profile with this id so a future L1 sweep can find
-- "older anon profiles that share this install_id" — that's the cue that
-- the previous owner of this install signed out and a new anon now owns
-- the device, leaving the prior row orphaned.
--
-- Nullable: the value is populated on first /v1/me from a header-aware
-- client, so any row created before that handshake reads NULL. Partial
-- index because cleanup queries always read `WHERE install_id = ?` —
-- indexing the NULL rows would waste space on entries that can't match.

ALTER TABLE profiles
    ADD COLUMN install_id UUID NULL;

CREATE INDEX profiles_install_id_idx
    ON profiles (install_id)
    WHERE install_id IS NOT NULL;
