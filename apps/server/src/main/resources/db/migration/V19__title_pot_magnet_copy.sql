-- ─────────────────────────────────────────────────────────────────────────────
-- V19: Realign title_pot_magnet copy with the BB-relative POT_5000 criterion.
--
-- V17 seeded `title_pot_magnet` with the description "Unlocked by sitting at
-- a 5,000-chip pot." That copy was correct against the V1 absolute-chip
-- POT_5000 criterion (target 5,000 chips). The achievement has since been
-- re-anchored to BB multiples (≥ 25× big blind) per the V1.x audit in
-- [docs/achievements-spec.md] — the absolute threshold misread badly at
-- high stake tiers (trivial on Challenging, impossible on Practice).
--
-- The title description shows in My Items (`MyItemsScreen` renders it
-- under the row), so the stale copy is user-facing wrong.
--
-- Only the `en` description changes; `title_by_locale`, `subtitle_by_locale`,
-- and the ES side stay as they were (no Spanish description was seeded in
-- V17 — the catalog matcher falls back to EN, so we only have one string
-- to correct).
-- ─────────────────────────────────────────────────────────────────────────────

UPDATE products
SET description_by_locale = '{"en":"Unlocked by sitting at a pot 25× the big blind. Shows under your name at the table — a quiet flex other players won''t see in the shop."}'::jsonb
WHERE id = 'title_pot_magnet';
