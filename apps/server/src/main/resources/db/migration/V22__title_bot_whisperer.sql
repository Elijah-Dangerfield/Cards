-- ─────────────────────────────────────────────────────────────────────────────
-- V22: Seed `title_bot_whisperer` — the unlock-only title granted when the
-- player beats all five bot personalities 10 times each (capstone of the
-- BEAT_JANE_10 / BEAT_DAVID_10 / BEAT_GINA_10 / BEAT_STEVE_10 / BEAT_MIKE_10
-- chain). The capstone achievement itself (`AchievementId.BOT_WHISPERER`)
-- is defined client-side in `AchievementRegistry.kt`; this row is the
-- cosmetic it grants.
--
-- Follows the V17 / V20 pattern for unlock-only titles + card backs:
--   - `unlock_only = TRUE` keeps it out of the shop list (PostgresProductCatalogSource.read
--     filters `WHERE unlock_only = FALSE`) but reachable via `readById` for grants.
--   - `is_equippable = TRUE` so the player can toggle it on from My Items.
--   - `cost_chips = 0` because the chip_offer kind constraint requires a non-null cost.
--   - `unlock_level = 1` mirrors the other unlock-only titles — gating is via the
--     achievement, not a level threshold.
--
-- Sort order 750 sits just below the V17 unlock-only titles (730 / 740) so
-- any future admin toggle to make them purchasable would land them together.
--
-- Client wires up `title_bot_whisperer → "Bot Whisperer"` in
-- `titleForProductId` (EquippedFelt.kt) and `BOT_WHISPERER → title_bot_whisperer`
-- in `ClientGrantableAchievements.Default` so the achievement-grant POST
-- maps to this row's id.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'title_bot_whisperer', 'chip_offer', 750, '🎓', FALSE, NULL,
    '{"en":"Bot Whisperer","es":"Susurrador de bots"}'::jsonb,
    '{"en":"Player title · earned","es":"Título de jugador · ganado"}'::jsonb,
    '{"en":"Unlocked by beating all five bot personalities 10 times each. Shows under your name at the table."}'::jsonb,
    'title.bot_whisperer', 0, 1, TRUE, TRUE
);
