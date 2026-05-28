-- ─────────────────────────────────────────────────────────────────────────────
-- V41: Seed `title_royalty` — unlock-only title granted on
-- `AchievementId.SHOW_ROYAL_FLUSH` ("Royal flush", LEGENDARY mystery, icon 👑).
--
-- Continues the [docs/todo.md §A "Catalog gating — unlock-only vs purchasable"
-- — More earnable cosmetic pairings ...] sweep. Royal flush is the rarest
-- hand in poker; pairing it with a singular vanity title makes the mystery
-- unlock land with a permanent flex the player carries under their name at
-- the table. The achievement was previously the only LEGENDARY in the
-- registry without a cosmetic complement.
--
-- Pairing rationale: every EPIC unlock now grants a cosmetic (POT_5000 →
-- title_pot_magnet, COMEBACK_FROM_5BB → title_short_stack_hero,
-- DONT_CALL_IT_COMEBACK → cardback_comeback_kid, BOT_WHISPERER →
-- title_bot_whisperer, REACH_LEVEL_25 → title_felt_veteran, plus the
-- seven `*_10` and `*_5` EPIC chain capstones via emote packs). The two
-- LEGENDARY mysteries (SHOW_STRAIGHT_FLUSH / SHOW_ROYAL_FLUSH) were the
-- only rarity-EPIC-or-above achievements still XP-only — this row closes
-- the royal-flush gap. The straight-flush pairing lands in a separate
-- migration.
--
-- Pick "Royalty" (single word, regal, the achievement *is* the royal hand)
-- over alternatives like "Crowned" (verb-form reads as transient state,
-- not a permanent flex), "Royal Flush" (literal hand name — repeats the
-- achievement label, less satisfying as a vanity title), or "Royalty
-- Owner" (matches the verbose precedent like "Pot Magnet" but reads
-- redundant — the title alone communicates ownership). Single-word
-- distinct titles already exist in the shipped catalog (the V17
-- `title_shark` / `title_high_roller`) so the shape isn't novel.
--
-- Mode: SHOW_ROYAL_FLUSH carries the default `AchievementMode.EITHER` —
-- it fires whenever the human reaches showdown with a royal flush
-- regardless of bot vs MP table. `ClientGrantableAchievements.Default`
-- adds SHOW_ROYAL_FLUSH to `clientGrantable` (not `serverWitnessed`)
-- because hand resolution is client-driven today; Phase 4.2
-- server-authoritative gameplay will migrate the entry if/when the
-- server takes ownership of showdown resolution.
--
-- Follows the V17 / V20 / V22 / V35 unlock-only-title pattern:
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
-- Sort order 770 sits directly below `title_felt_veteran` (V35, 760) so
-- the unlock-only titles cluster is preserved if a future admin toggle
-- ever surfaces them.
--
-- Client wiring lands in the same commit:
--   * `titleForProductId("title_royalty") = "Royalty"` in the client-side
--     title switchboard.
--   * `cosmeticRewardFor(AchievementId.SHOW_ROYAL_FLUSH)` returns
--     `CosmeticReward("title_royalty", "Royalty title")` for the
--     in-game unlock callout.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"SHOW_ROYAL_FLUSH" → "title_royalty"` so the client grant POST
--     resolves to this row.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'title_royalty', 'chip_offer', 770, '👑', FALSE, NULL,
    '{"en":"Royalty","es":"Realeza"}'::jsonb,
    '{"en":"Player title · earned","es":"Título de jugador · ganado"}'::jsonb,
    '{"en":"Unlocked by showing a royal flush at showdown — the rarest hand in poker. Shows under your name at the table.","es":"Desbloqueado al mostrar una escalera real en la confrontación — la mano más rara en el póker. Aparece bajo tu nombre en la mesa."}'::jsonb,
    'title.royalty', 0, 1, TRUE, TRUE
);
