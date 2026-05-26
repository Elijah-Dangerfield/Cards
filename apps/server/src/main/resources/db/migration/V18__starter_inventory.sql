-- ─────────────────────────────────────────────────────────────────────────────
-- V18: Starter inventory — seed the catalog rows + backfill existing users.
--
-- Until now, only `felt_default` shipped as a starter (V16). Every `GET /v1/me`
-- re-fired `recordEarnedGrant(felt_default)` to land it in inventory — cheap
-- per call but conceptually wrong (the earned-grant pathway is for achievement
-- rewards, not "you get this just for signing up") and load-bearing on the
-- assumption that the only starter row is the felt.
--
-- This migration formalises the starter-kit concept:
--   * Adds two more catalog rows so every user has a starter cosmetic in each
--     equippable family that has one — felt, card back, emoji-blast pack.
--     `unlock_only = TRUE` keeps all three out of the shop (the existing
--     `unlock_only = FALSE` filter in `PostgresProductCatalogSource.read`
--     already enforces this).
--   * Backfills inventory rows for every profile that exists today, so the
--     server can drop the per-request grant block in `MeRoutes.kt` without
--     stranding existing users on partial starter kits.
--
-- After this migration, `PostgresProfileRepository.findOrCreate` is the sole
-- writer for starter inventory: it inserts the three rows atomically with the
-- new profile row. The `MeRoutes.kt` grant path is removed in the same commit.
--
-- Sort order 195 places `cardback_default` adjacent to `felt_default` (190);
-- emoji-pack starter at 295 sits just below the existing emote-pack rows
-- (300-330) so any future admin toggle to make any of them shoppable groups
-- the row with its siblings.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES
(
    'cardback_default', 'chip_offer', 195, '🎴', FALSE, NULL,
    '{"en":"Default Card Back","es":"Reverso predeterminado"}'::jsonb,
    '{"en":"Card back","es":"Reverso de carta"}'::jsonb,
    '{"en":"The classic blue card back you start with. Equip from your items."}'::jsonb,
    'cardback.default', 0, 1, TRUE, TRUE
),
(
    'emoji_pack_starter', 'chip_offer', 295, '👋', FALSE, NULL,
    '{"en":"Starter Emoji Pack","es":"Paquete inicial de emojis"}'::jsonb,
    '{"en":"Emotes · 4 reactions","es":"Emotes · 4 reacciones"}'::jsonb,
    '{"en":"Unlocks 👋 👍 🎉 😀 — friendly reactions to get you started. Send from the in-game emote tray."}'::jsonb,
    'emoji_pack.starter', 0, 1, FALSE, TRUE
);

-- Backfill: every existing profile gets the three starter rows. `ON CONFLICT
-- DO NOTHING` so re-running the migration after a future schema repair is a
-- no-op, and so users who already had `felt_default` granted via the old
-- per-/v1/me grant path keep their row's original `purchased_at` timestamp.
--
-- `purchased_at = profiles.created_at` keeps the "you've owned this since you
-- joined" provenance honest in My Items rather than stamping it with the
-- migration time. `acquisition_source = 'earned'` lines up with the
-- `recordEarnedGrant` pathway the server used before this commit.
INSERT INTO inventory (user_id, product_id, cost_chips_at_purchase, purchased_at, acquisition_source)
SELECT p.user_id, starter.product_id, 0, p.created_at, 'earned'
FROM profiles p
CROSS JOIN (
    VALUES ('felt_default'), ('cardback_default'), ('emoji_pack_starter')
) AS starter(product_id)
ON CONFLICT (user_id, product_id) DO NOTHING;
