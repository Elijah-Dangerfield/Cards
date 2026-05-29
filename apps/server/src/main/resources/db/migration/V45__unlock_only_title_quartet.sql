-- ─────────────────────────────────────────────────────────────────────────────
-- V45: Seed `title_quartet` — unlock-only title granted on
-- `AchievementId.SHOW_FOUR_OF_KIND` ("Quads", EPIC, icon 🍀).
--
-- Pairs with V44's full-house title to close the [docs/todo.md §A "Catalog
-- gating — unlock-only vs purchasable"] EPIC-showdown gap. With this row,
-- every rarity-EPIC-or-above showdown achievement carries a cosmetic
-- complement — the showdown rarity ladder (Flush=RARE → Full House=EPIC →
-- Quads=EPIC → Straight Flush=LEGENDARY → Royal Flush=LEGENDARY) is now
-- fully paired above the RARE tier.
--
-- Pick "Quartet" — single elegant word capturing "four of one kind" via
-- the musical metaphor (a quartet is exactly four), mirroring V41's
-- "Royalty" single-word approach for the royal flush. The metaphor reads
-- without ambiguity even outside poker: any reader recognizes "quartet"
-- as four matching things. Alternatives considered: "Quad King" (two
-- words, "King" feels disconnected from the four-of-a-kind concept —
-- could be confused with the literal King-rank); "Quads" (literal
-- achievement name — same redundancy V41/V42/V44 avoided); "Four-Star"
-- (military rank metaphor, but four-star already carries an unrelated
-- review/quality meaning that dilutes the poker context); "Quad Squad"
-- (rhyme is fun but reads juvenile under a player's name).
--
-- Mode: SHOW_FOUR_OF_KIND carries the default `AchievementMode.EITHER` —
-- fires whenever the human reaches showdown with four of a kind
-- regardless of bot vs MP table. `ClientGrantableAchievements.Default`
-- adds SHOW_FOUR_OF_KIND to `clientGrantable` (not `serverWitnessed`)
-- because hand resolution is client-driven today; Phase 4.2
-- server-authoritative gameplay will migrate the entry if/when the
-- server takes ownership of showdown resolution.
--
-- Follows the V17 / V20 / V22 / V35 / V41 / V42 / V44 unlock-only-title
-- pattern:
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
-- Sort order 800 sits directly below `title_full_boat` (V44, 790) so the
-- unlock-only titles cluster is preserved if a future admin toggle ever
-- surfaces them.
--
-- Client wiring lands in the same commit:
--   * `titleForProductId("title_quartet") = "Quartet"` in the client-side
--     title switchboard.
--   * `cosmeticRewardFor(AchievementId.SHOW_FOUR_OF_KIND)` returns
--     `CosmeticReward("title_quartet", "Quartet title")` for the in-game
--     unlock callout.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"SHOW_FOUR_OF_KIND" → "title_quartet"` so the client grant POST
--     resolves to this row.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'title_quartet', 'chip_offer', 800, '🍀', FALSE, NULL,
    '{"en":"Quartet","es":"Cuarteto"}'::jsonb,
    '{"en":"Player title · earned","es":"Título de jugador · ganado"}'::jsonb,
    '{"en":"Unlocked by showing four of a kind at showdown — four cards of the same rank. Shows under your name at the table.","es":"Desbloqueado al mostrar póker en la confrontación — cuatro cartas del mismo valor. Aparece bajo tu nombre en la mesa."}'::jsonb,
    'title.quartet', 0, 1, TRUE, TRUE
);
