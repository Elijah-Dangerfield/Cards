-- ─────────────────────────────────────────────────────────────────────────────
-- V42: Seed `title_suited_run` — unlock-only title granted on
-- `AchievementId.SHOW_STRAIGHT_FLUSH` ("Straight flush", LEGENDARY mystery,
-- icon 🌊).
--
-- Closes the LEGENDARY-mystery showdown pairing pair. V41 paired the rarer
-- `SHOW_ROYAL_FLUSH` with `title_royalty`; this row paves the second
-- LEGENDARY (`SHOW_STRAIGHT_FLUSH`) with its own vanity title so the two
-- rarest hands in poker both grant a permanent flex under the player's
-- name. After this row lands, every rarity-EPIC-or-above achievement in
-- the registry has a cosmetic complement (only the EPIC `SHOW_FULL_HOUSE`
-- / `SHOW_FOUR_OF_KIND` remain unpaired; flagged in §A but lower-priority
-- than the LEGENDARYs).
--
-- Pick "Suited Run" over alternatives like "Pure Run" ("pure" is generic),
-- "Hot Streak" (could apply to any win streak), "Straight Flush" /
-- "Straight Flusher" (literal hand name — same redundancy reason V41
-- avoided "Royal Flush"), or "Sequence Master" (too verbose, three
-- syllables on the second word). "Suited Run" uses two poker-specific
-- terms — "suited" means same-suit in poker without ambiguity, "run"
-- means a sequence of consecutive cards in many card games. The pair
-- captures both halves of the straight-flush concept without repeating
-- the literal hand name, in the same shape V41's "Royalty" captured the
-- royal-flush concept without repeating "Royal Flush".
--
-- Mode: SHOW_STRAIGHT_FLUSH carries the default `AchievementMode.EITHER`
-- — it fires whenever the human reaches showdown with a straight flush
-- regardless of bot vs MP table. `ClientGrantableAchievements.Default`
-- adds SHOW_STRAIGHT_FLUSH to `clientGrantable` (not `serverWitnessed`)
-- because hand resolution is client-driven today; Phase 4.2
-- server-authoritative gameplay will migrate the entry if/when the server
-- takes ownership of showdown resolution.
--
-- Follows the V17 / V20 / V22 / V35 / V41 unlock-only-title pattern:
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
-- Sort order 780 sits directly below `title_royalty` (V41, 770) so the
-- unlock-only titles cluster is preserved if a future admin toggle ever
-- surfaces them.
--
-- Client wiring lands in the same commit:
--   * `titleForProductId("title_suited_run") = "Suited Run"` in the
--     client-side title switchboard.
--   * `cosmeticRewardFor(AchievementId.SHOW_STRAIGHT_FLUSH)` returns
--     `CosmeticReward("title_suited_run", "Suited Run title")` for the
--     in-game unlock callout.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"SHOW_STRAIGHT_FLUSH" → "title_suited_run"` so the client grant
--     POST resolves to this row.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'title_suited_run', 'chip_offer', 780, '🌊', FALSE, NULL,
    '{"en":"Suited Run","es":"Corrida del palo"}'::jsonb,
    '{"en":"Player title · earned","es":"Título de jugador · ganado"}'::jsonb,
    '{"en":"Unlocked by showing a straight flush at showdown — five suited cards in sequence. Shows under your name at the table.","es":"Desbloqueado al mostrar una escalera de color en la confrontación — cinco cartas del mismo palo en secuencia. Aparece bajo tu nombre en la mesa."}'::jsonb,
    'title.suited_run', 0, 1, TRUE, TRUE
);
