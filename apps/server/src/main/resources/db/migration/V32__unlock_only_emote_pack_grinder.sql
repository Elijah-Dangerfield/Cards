-- ─────────────────────────────────────────────────────────────────────────────
-- V32: Seed `emotes_grinder` — sixth unlock-only emote pack.
--
-- Continues the [docs/todo.md §A "Catalog gating — unlock-only vs purchasable"
-- — More emote / blast-pack unlocks] sweep. Prior pairings: V25 paired
-- `BUST_DEALT_5` → `emotes_eliminator`, V27 paired `TRIPLE_UP` →
-- `emotes_baller`, V28 paired `NO_BUST_100` → `emotes_iron_stack`, V29
-- paired `WIN_BY_FOLD_10` → `emotes_convincer`, V30 paired `GOOD_FOLD_25`
-- → `emotes_disciplined`.
--
-- This row covers the long-tail-grind pairing the catalog had no answer
-- for. V30's commit footer rejected `BEAT_*_10` (five new packs in one
-- ship to avoid an inconsistent half-rollout) and `SHOW_*` (emoji
-- collisions with `emotes_fierce` / `emotes_royal`). `AchievementId.HANDS_1000`
-- ("Grinder", EPIC, EITHER mode, name = "Grinder", icon 🏆) is the
-- single largest XP-only achievement in the registry that lacks a
-- cosmetic complement — the worker doctrine "epic capstones deserve a
-- memorable cosmetic" applied to every other EPIC tier (BOT_WHISPERER →
-- title_bot_whisperer, POT_5000 → title_pot_magnet, NO_BUST_100 →
-- emotes_iron_stack, TRIPLE_UP → emotes_baller); HANDS_1000 is the
-- outlier and this row fixes it.
--
-- Pairing rationale: 1,000 hands is a real time investment. The
-- "grinder" archetype (steady, slow, profit-extractor, hours-at-the-
-- table) deserves an emote set the player can blast to signal "I'm
-- still here, still chipping away." Set is ☕ ⛏️ 🛠️ ⌛:
--   * ☕ coffee — the universal grinder symbol; player keeps showing up.
--   * ⛏️ pickaxe — chipping the table down a hand at a time. Doubles
--     as the pack icon since it's the most evocative grinder glyph.
--   * 🛠️ tools — "I'm just working" (the line every grinder uses).
--   * ⌛ hourglass — patience, time invested, the long form of the
--     game.
--
-- None of those four overlap any of the nine prior pack sets (drama
-- 💃🧂🎭🤦, cute 🥺🥰😇🤗, fierce 😤🔥💀😎, royal 👑🃏♠️♥️, eliminator
-- 🪦⚰️👻🥀, baller 💸💎🤑📈, iron stack 🛡️🧱🗿🦾, convincer 🪄🎩😏🤫,
-- disciplined 🧘🦉👁️🪞). They also don't collide with any achievement
-- icon — 🐢 (BEAT_STEVE_10) and 📊 (DOUBLE_UP) were two strong grinder-
-- coded candidates but got dropped to keep the pack-vs-achievement
-- icon namespace clean. Achievement HANDS_1000's own icon stays 🏆;
-- the pack icon is the differentiator that the unlock callout flags
-- as "new cosmetic" without collapsing with the achievement medallion.
--
-- The blast pool grows by four real options the moment this lands in
-- inventory — players have meaningful new content to spend on the
-- table.
--
-- Mode: HANDS_1000 carries `mode = AchievementMode.EITHER` (defaults
-- to it — no override) so the grant fires whether the 1000th hand
-- lands at a bot table or an MP table. Unlocking the pack via either
-- path is the right call — the achievement is "you played a lot," not
-- "you played a lot of bot hands." Once Phase 4.2 server-authoritative
-- gameplay lands, the MP path will fire server-side and the BOTS path
-- via the client POST — `ClientGrantableAchievements.Default` keeps
-- HANDS_1000 in `clientGrantable` (not `serverWitnessed`) because the
-- counter is on-device today.
--
-- Follows the V20 / V25 / V27 / V28 / V29 / V30 unlock-only-cosmetic
-- pattern:
--   - `unlock_only = TRUE` keeps it out of the shop catalog
--     (`PostgresProductCatalogSource.read` filters `WHERE unlock_only = FALSE`)
--     but reachable via `readById` for the achievement grant.
--   - `is_equippable = FALSE` matches every other emote pack — packs
--     expand the blast pool, no per-emote equip toggle.
--   - `cost_chips = 0` because the chip_offer kind constraint requires a
--     non-null cost; `unlock_only` is what actually keeps it off the shelf.
--   - `unlock_level = 1` mirrors the other unlock-only cosmetics — gating
--     is via the achievement, not a level threshold.
--
-- Sort order 390 sits directly after the V30 disciplined pack (380), so
-- the unlock-only emote packs are visually grouped if the shelf ever
-- surfaces them in catalog order.
--
-- Client wiring lands in the same commit:
--   * `EmojiPackCatalog.PackEmojis` gains `"emotes_grinder" → ☕⛏️🛠️⌛`.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"HANDS_1000" → "emotes_grinder"` so the client grant POST
--     resolves to this row.
--   * `cosmeticRewardFor(AchievementId.HANDS_1000)` mirrors the
--     mapping for the in-game unlock callout.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'emotes_grinder', 'chip_offer', 390, '⛏️', FALSE, NULL,
    '{"en":"Grinder Emote Pack","es":"Paquete del grinder"}'::jsonb,
    '{"en":"Emote pack · earned","es":"Paquete de emotes · ganado"}'::jsonb,
    '{"en":"Unlocked by playing 1,000 hands — the long-tail commitment most players never see. Unlocks ☕ ⛏️ 🛠️ ⌛ — for the seat that just keeps chipping away. Send from the in-game emote tray.","es":"Desbloqueado al jugar 1.000 manos — el compromiso a largo plazo que la mayoría de los jugadores nunca ve. Desbloquea ☕ ⛏️ 🛠️ ⌛ — para el asiento que sigue trabajando sin parar. Envíalas desde la bandeja de emotes."}'::jsonb,
    'emotes.grinder', 0, 1, FALSE, TRUE
);
