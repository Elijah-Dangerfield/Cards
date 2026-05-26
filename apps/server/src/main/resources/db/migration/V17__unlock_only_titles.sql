-- ─────────────────────────────────────────────────────────────────────────────
-- V17: Seed unlock-only player titles tied to existing EPIC achievements.
--
-- Per [docs/todo.md §A "Seed an opening pool of unlock-only catalog content"]:
-- the V1 unlock-only catalog is structurally ready (V10 added the flag, V16
-- seeded `felt_default` as the first unlock-only row) but otherwise empty,
-- so the [product-spec.md §4.2] promise that legendary achievements grant
-- exclusive cosmetics never reads true. Titles are the cheapest unlock
-- shape — no new visual asset, just a string under the player's name at
-- the table — so they're the right shape to seed first while we wait on
-- the achievement-reward → `recordEarnedGrant` wire-up.
--
-- Both rows pair with existing EPIC-rarity achievements in
-- [AchievementRegistry.kt]:
--   * `title_pot_magnet`       ↔ AchievementId.POT_5000 ("Whale pot")
--   * `title_short_stack_hero` ↔ AchievementId.COMEBACK_FROM_5BB ("Short stack hero")
--
-- Picked the two EPICs that already exist in the registry and are *not*
-- mystery-flagged on the player title surface (the player can recognise
-- the title without having to know the achievement spoiler). The third
-- candidate from the todo (`title_comeback_kid` ↔ DONT_CALL_IT_COMEBACK)
-- is left to a follow-up so we don't crowd the catalog with three near-
-- identical achievement-comeback titles in one pass.
--
-- `unlock_only = TRUE` keeps both rows out of the shop catalog
-- (`PostgresProductCatalogSource.read` filters `WHERE unlock_only = FALSE`)
-- but reachable via `readById` for inventory grants.
-- `cost_chips = 0` because the chip_offer kind constraint requires a
-- non-null cost; the unlock_only flag is what actually keeps it off the
-- shelf. `is_equippable = TRUE` so once granted the user can toggle the
-- title on from My Items, same as the purchasable titles.
--
-- Sort order 730+ groups these just below the existing shoppable titles
-- (700–720) so any future admin toggle to make them purchasable would
-- land them adjacent to their siblings.
--
-- Client mapping: `titleForProductId` ([EquippedFelt.kt]) is extended in
-- the same commit to render "Pot Magnet" / "Short Stack Hero" when the
-- corresponding row appears in inventory. The achievement-reward → grant
-- pathway itself remains on the todo (`Wire the earned-grant path`); this
-- migration just makes the catalog content ready for it to land.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES
(
    'title_pot_magnet', 'chip_offer', 730, '🧲', FALSE, NULL,
    '{"en":"Pot Magnet","es":"Imán de bote"}'::jsonb,
    '{"en":"Player title · earned","es":"Título de jugador · ganado"}'::jsonb,
    '{"en":"Unlocked by sitting at a 5,000-chip pot. Shows under your name at the table — a quiet flex other players won''t see in the shop."}'::jsonb,
    'title.pot_magnet', 0, 1, TRUE, TRUE
),
(
    'title_short_stack_hero', 'chip_offer', 740, '📈', FALSE, NULL,
    '{"en":"Short Stack Hero","es":"Héroe de pila corta"}'::jsonb,
    '{"en":"Player title · earned","es":"Título de jugador · ganado"}'::jsonb,
    '{"en":"Unlocked by doubling up from 5 big blinds or less. Shows under your name at the table."}'::jsonb,
    'title.short_stack_hero', 0, 1, TRUE, TRUE
);
