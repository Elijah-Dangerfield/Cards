-- V93: A standing table outlives its owner's account. Deleting the owner used to
-- cascade the row away and free a code that friends may still have saved; now the
-- owner is cleared and the server treats an ownerless table as closed.
ALTER TABLE standing_tables ALTER COLUMN owner_user_id DROP NOT NULL;
ALTER TABLE standing_tables DROP CONSTRAINT standing_tables_owner_user_id_fkey;
ALTER TABLE standing_tables ADD CONSTRAINT standing_tables_owner_user_id_fkey
    FOREIGN KEY (owner_user_id) REFERENCES auth.users(id) ON DELETE SET NULL;
