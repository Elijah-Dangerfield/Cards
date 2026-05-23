-- V11: Foreign keys to auth.users.
--
-- Every per-user table now declares its `user_id` as a FK to
-- `auth.users(id) ON DELETE CASCADE`. Two things change because of this:
--
--   1. **Referential integrity at the database level.** A `user_id`
--      that doesn't exist in `auth.users` can no longer be inserted.
--      Previous migrations declined this on purpose because
--      Testcontainers had no `auth` schema; that's now solved by
--      `init-auth.sql` in the test resources.
--
--   2. **ON DELETE CASCADE for account deletion.** Deleting a row in
--      `auth.users` (either via Supabase Admin API from our DELETE
--      /v1/me handler or via the Supabase dashboard) now wipes the
--      user's profile + wallet + wallet_events + inventory + equipment
--      + user_messages in a single atomic step. Previously the cleanup
--      lived in the app layer (`*Repository.deleteAllForUser`) and a
--      dashboard-side delete would leave orphans.
--
-- See docs/decisions.md 2026-05-23 for the deletion-policy thinking
-- and why hard-delete-now (vs. soft-delete with a recovery window) is
-- the V1 choice.

ALTER TABLE profiles
    ADD CONSTRAINT profiles_user_id_fk
    FOREIGN KEY (user_id)
    REFERENCES auth.users(id)
    ON DELETE CASCADE;

ALTER TABLE wallets
    ADD CONSTRAINT wallets_user_id_fk
    FOREIGN KEY (user_id)
    REFERENCES auth.users(id)
    ON DELETE CASCADE;

-- wallet_events FKs auth.users directly (not wallets) so a delete cascades
-- even if a hypothetical future code path purged a wallet row first.
ALTER TABLE wallet_events
    ADD CONSTRAINT wallet_events_user_id_fk
    FOREIGN KEY (user_id)
    REFERENCES auth.users(id)
    ON DELETE CASCADE;

ALTER TABLE inventory
    ADD CONSTRAINT inventory_user_id_fk
    FOREIGN KEY (user_id)
    REFERENCES auth.users(id)
    ON DELETE CASCADE;

ALTER TABLE equipment
    ADD CONSTRAINT equipment_user_id_fk
    FOREIGN KEY (user_id)
    REFERENCES auth.users(id)
    ON DELETE CASCADE;

ALTER TABLE user_messages
    ADD CONSTRAINT user_messages_user_id_fk
    FOREIGN KEY (user_id)
    REFERENCES auth.users(id)
    ON DELETE CASCADE;
