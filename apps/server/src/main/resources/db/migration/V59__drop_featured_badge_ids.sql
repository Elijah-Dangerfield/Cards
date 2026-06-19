-- ─────────────────────────────────────────────────────────────────────────────
-- V59: drop `profiles.featured_badge_ids`.
--
-- V54 added this column for the "feature up to 3 earned achievements on your
-- Player Card" picker. The Player Card was reworked to render equipped
-- badges/titles (the unified cosmetics system) instead of featured
-- achievements, which orphaned the picker and its whole plumbing — client
-- VM/state, MeDto/PatchMeRequest field, and the route-layer cap/de-dup.
-- Nothing reads or writes this column anymore; it's torn out alongside this
-- migration. The design lives on in git history (V54 + the badges rework)
-- if a future surface wants it back.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE profiles
    DROP COLUMN IF EXISTS featured_badge_ids;
