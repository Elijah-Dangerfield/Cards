-- ─────────────────────────────────────────────────────────────────────────────
-- V35: Seed `title_felt_veteran` — unlock-only title granted on
-- `AchievementId.REACH_LEVEL_25` ("Felt veteran" EPIC capstone of the level
-- milestone chain — REACH_LEVEL_5 → REACH_LEVEL_10 → REACH_LEVEL_25).
--
-- Continues the [docs/todo.md §A "Catalog gating — unlock-only vs purchasable"
-- — More emote / blast-pack unlocks beyond the seeded ...] sweep. The todo
-- floats two candidate pairings: per-bot `BEAT_*_10` signature packs (still
-- on hold — needs five new packs shipped in one ship to avoid an inconsistent
-- half-rollout), and REACH_LEVEL_25 as a level capstone (the todo's
-- recommendation is "a card back rather than an emote pack" but a *title* is
-- a closer match for the achievement name "Felt veteran" — and a single
-- titleForProductId entry is much smaller surface than a new CardBackStyle
-- enum value + paletteFor branch + PlayingCardBack renderer branch).
--
-- This row picks REACH_LEVEL_25, paired with a player title rather than a
-- card back so the cosmetic shape matches the achievement label literally.
-- The level-milestone chain has been the only EPIC without a cosmetic
-- complement: every other EPIC achievement carries one (BOT_WHISPERER →
-- title_bot_whisperer, POT_5000 → title_pot_magnet, NO_BUST_100 →
-- emotes_iron_stack, TRIPLE_UP → emotes_baller, HANDS_1000 → emotes_grinder,
-- COMEBACK_FROM_5BB → title_short_stack_hero, DONT_CALL_IT_COMEBACK →
-- cardback_comeback_kid, CHALLENGING_10_WINS → emotes_tactician). This row
-- closes that gap.
--
-- Mode: REACH_LEVEL_25's criterion is `Custom(key = CURRENT_LEVEL, target =
-- 25)` and carries the default `AchievementMode.EITHER` — the level counter
-- ticks on either bot or MP hands, so the unlock is reachable from both
-- surfaces. `ClientGrantableAchievements.Default` keeps REACH_LEVEL_25 in
-- `clientGrantable` (not `serverWitnessed`) because XP / level state is
-- client-driven today; Phase 4.2 server-authoritative gameplay will migrate
-- the entry if/when the server takes ownership of the level counter.
--
-- Follows the V17 / V20 / V22 unlock-only-cosmetic pattern:
--   - `unlock_only = TRUE` keeps it out of the shop catalog
--     (`PostgresProductCatalogSource.read` filters `WHERE unlock_only = FALSE`)
--     but reachable via `readById` for the achievement grant.
--   - `is_equippable = TRUE` matches every other title — equip from My Items
--     to show under the player's name at the table.
--   - `cost_chips = 0` because the chip_offer kind constraint requires a
--     non-null cost; `unlock_only` is what actually keeps it off the shelf.
--   - `unlock_level = 1` mirrors the other unlock-only titles — gating is
--     via the achievement, not a level threshold (the achievement itself is
--     the level gate).
--
-- Sort order 760 sits directly below `title_bot_whisperer` (V22, 750) so the
-- unlock-only titles are visually grouped if the shelf ever surfaces them.
--
-- Client wiring lands in the same commit:
--   * `titleForProductId("title_felt_veteran") = "Felt Veteran"` in the
--     client-side title switchboard.
--   * `cosmeticRewardFor(AchievementId.REACH_LEVEL_25)` returns
--     `CosmeticReward("title_felt_veteran", "Felt Veteran title")` for the
--     in-game unlock callout.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"REACH_LEVEL_25" → "title_felt_veteran"` so the client grant POST
--     resolves to this row.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'title_felt_veteran', 'chip_offer', 760, '💎', FALSE, NULL,
    '{"en":"Felt Veteran","es":"Veterano del fieltro"}'::jsonb,
    '{"en":"Player title · earned","es":"Título de jugador · ganado"}'::jsonb,
    '{"en":"Unlocked by reaching level 25. Shows under your name at the table — the seat that has been around the table a while.","es":"Desbloqueado al alcanzar el nivel 25. Aparece bajo tu nombre en la mesa — el asiento que lleva tiempo en la mesa."}'::jsonb,
    'title.felt_veteran', 0, 1, TRUE, TRUE
);
