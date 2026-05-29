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
-- Nullable: backfill-safe for any profile that existed before the column
-- landed (NULL means "not tagged yet"; the next /v1/me from a client that
-- sends the header overwrites). Once V1 ships, every profile gets tagged
-- on its first authenticated request, so steady-state is non-null.
--
-- Partial index: cleanup queries always read `WHERE install_id = ?`, so
-- indexing NULL entries would waste space on rows that can't match.

ALTER TABLE profiles
    ADD COLUMN install_id UUID NULL;

CREATE INDEX profiles_install_id_idx
    ON profiles (install_id)
    WHERE install_id IS NOT NULL;
