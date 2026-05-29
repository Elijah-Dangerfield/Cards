-- ─────────────────────────────────────────────────────────────────────────────
-- V44: Seed `title_full_boat` — unlock-only title granted on
-- `AchievementId.SHOW_FULL_HOUSE` ("Full house", EPIC, icon 🏠).
--
-- Closes the second half of the [docs/todo.md §A "Catalog gating —
-- unlock-only vs purchasable"] EPIC-showdown gap. Once this row plus V45
-- (quads) land, every rarity-EPIC-or-above showdown achievement carries a
-- cosmetic complement — completing the showdown ladder symmetry started
-- when V41/V42 paired the two LEGENDARY showdowns.
--
-- Pick "Full Boat" — "boat" is universal poker slang for a full house
-- (e.g. "I rivered a boat"), and "Full Boat" is the longer canonical form
-- of the phrase. It mirrors V42's "Suited Run" approach: two
-- poker-specific words capturing the hand concept without repeating the
-- literal "Full House" achievement label. Alternatives considered:
-- "Housemaster" (decent but generic — could be a level-up title, doesn't
-- read as showdown-specific); "Full House" (literal hand name — same
-- redundancy V41/V42 avoided); "Boat" alone (too terse — single
-- common-noun reads as placeholder text under a player's name); "Boat
-- Captain" (two-word but "Captain" feels unrelated to the hand itself).
--
-- Mode: SHOW_FULL_HOUSE carries the default `AchievementMode.EITHER` —
-- fires whenever the human reaches showdown with a full house regardless
-- of bot vs MP table. `ClientGrantableAchievements.Default` adds
-- SHOW_FULL_HOUSE to `clientGrantable` (not `serverWitnessed`) because
-- hand resolution is client-driven today; Phase 4.2 server-authoritative
-- gameplay will migrate the entry if/when the server takes ownership of
-- showdown resolution.
--
-- Follows the V17 / V20 / V22 / V35 / V41 / V42 unlock-only-title pattern:
--   - `unlock_only = TRUE` keeps it out of the shop catalog
--     (`PostgresProductCatalogSource.read` filters `WHERE unlock_only = FALSE`)
--     but reachable via `readById` for the achievement grant.
--   - `is_equippable = TRUE` matches every other title — equip from My Items
--     to show under the player's name at the table.
--   - `cost_chips = 0` because the chip_offer kind constraint requires a
--     non-null cost; `unlock_only` is what actually keeps it off the shelf.
--   - `unlock_level = 1` mirrors the other unlock-only titles — gating is
--     via the achievement, not a level threshold.
--
-- Sort order 790 sits directly below `title_suited_run` (V42, 780) so the
-- unlock-only titles cluster is preserved if a future admin toggle ever
-- surfaces them.
--
-- Client wiring lands in the same commit:
--   * `titleForProductId("title_full_boat") = "Full Boat"` in the
--     client-side title switchboard.
--   * `cosmeticRewardFor(AchievementId.SHOW_FULL_HOUSE)` returns
--     `CosmeticReward("title_full_boat", "Full Boat title")` for the
--     in-game unlock callout.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"SHOW_FULL_HOUSE" → "title_full_boat"` so the client grant POST
--     resolves to this row.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'title_full_boat', 'chip_offer', 790, '🏠', FALSE, NULL,
    '{"en":"Full Boat","es":"Barco completo"}'::jsonb,
    '{"en":"Player title · earned","es":"Título de jugador · ganado"}'::jsonb,
    '{"en":"Unlocked by showing a full house at showdown — three of a kind plus a pair. Shows under your name at the table.","es":"Desbloqueado al mostrar un full en la confrontación — tres iguales más una pareja. Aparece bajo tu nombre en la mesa."}'::jsonb,
    'title.full_boat', 0, 1, TRUE, TRUE
);
