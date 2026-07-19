-- V87: Reason categories on player reports (MOD-2). The report flow gained a
-- bottom sheet where the reporter picks one or more reason tags (harassment,
-- cheating, offensive name, spam, …) alongside the optional free-text reason,
-- so a human moderator can filter reports by category.
--
-- Stored as a comma-joined list of canonical category keys in a single nullable
-- TEXT column — the volume is low and a moderator filters with a LIKE, so a
-- normalized child table or a Postgres array would be more machinery than this
-- earns. Nullable + no default: existing rows (and any report filed with no
-- category picked) stay NULL, exactly like `reason`.

ALTER TABLE player_reports ADD COLUMN reason_categories TEXT;
