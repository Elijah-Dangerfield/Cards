-- ─────────────────────────────────────────────────────────────────────────────
-- V34: Seed `emotes_tactician` — eighth unlock-only emote pack.
--
-- Continues the [docs/todo.md §A "Catalog gating — unlock-only vs purchasable"
-- — More emote / blast-pack unlocks] sweep. Prior pairings: V25 paired
-- `BUST_DEALT_5` → `emotes_eliminator`, V27 paired `TRIPLE_UP` →
-- `emotes_baller`, V28 paired `NO_BUST_100` → `emotes_iron_stack`, V29
-- paired `WIN_BY_FOLD_10` → `emotes_convincer`, V30 paired `GOOD_FOLD_25`
-- → `emotes_disciplined`, V32 paired `HANDS_1000` → `emotes_grinder`,
-- V33 paired `DOUBLE_UP` → `emotes_doubler`.
--
-- V33's commit footer named two remaining candidate pairings still on the
-- table: `BEAT_*_10` (rejected — needs five new packs in one ship to avoid
-- an inconsistent half-rollout) and `CHALLENGING_10_WINS` (deferred — risks
-- emoji collisions with `emotes_fierce` 😤🔥💀😎 since "challenging" tilts
-- toward the same combat tonality). This row picks the second option — the
-- collision risk turns out to be bounded once the pack leans on a
-- *strategy* archetype ("you didn't out-fight Challenging, you out-thought
-- it") rather than a *combat* archetype, which keeps the pack visually
-- distinct from `emotes_fierce` even though both packs share the same
-- difficulty-tier moment.
--
-- `AchievementId.CHALLENGING_10_WINS` ("Sharpened up", EPIC, BOTS mode,
-- icon 🗡️) is the EPIC capstone of the difficulty tier and the last
-- unpaired EPIC achievement in the registry: every other EPIC carries a
-- cosmetic complement (BOT_WHISPERER → title_bot_whisperer, POT_5000 →
-- title_pot_magnet, NO_BUST_100 → emotes_iron_stack, TRIPLE_UP →
-- emotes_baller, HANDS_1000 → emotes_grinder, COMEBACK_FROM_5BB →
-- title_short_stack_hero, DONT_CALL_IT_COMEBACK → cardback_comeback_kid,
-- DOUBLE_UP → emotes_doubler at RARE — same call). This row closes that
-- gap.
--
-- Pairing rationale: 10 wins on Challenging is a meaningful skill bar —
-- the bots play tighter, raise harder, and bluff less. The achievement
-- name is "Sharpened up"; the unlock pack leans into the *thinking-player*
-- read ("I didn't get lucky, I read the table") rather than the brute-
-- force read. The "Tactician" archetype gets emojis the player can blast
-- to signal a called shot, a precision fold, or a read landed clean. Set
-- is ♟️ 🦅 🥷 🏹:
--   * ♟️ chess pawn — the strategy glyph; "I thought a move ahead." Doubles
--     as the pack icon since it reads most distinctly from the achievement
--     medallion 🗡️ and from the V25/V33 combat-tier packs.
--   * 🦅 eagle — precision, watching the table from above before striking.
--   * 🥷 ninja — skill-based win, no luck, no noise.
--   * 🏹 bow and arrow — calling the shot, then landing it.
--
-- Why these specifically avoid `emotes_fierce` (😤🔥💀😎) tonal collision:
-- the fierce pack is *aggression* ("I'm coming for you"); the tactician
-- pack is *patience* ("I had you read the whole time"). Two distinct
-- emotional registers around the same achievement moment so a player
-- owning both packs feels them as additive, not redundant.
--
-- None of those four overlap any of the eleven prior pack sets (drama
-- 💃🧂🎭🤦, cute 🥺🥰😇🤗, fierce 😤🔥💀😎, royal 👑🃏♠️♥️, eliminator
-- 🪦⚰️👻🥀, baller 💸💎🤑📈, iron stack 🛡️🧱🗿🦾, convincer 🪄🎩😏🤫,
-- disciplined 🧘🦉👁️🪞, grinder ☕⛏️🛠️⌛, doubler 🚀⏫🎯💰). They also
-- don't collide with any achievement icon — explicitly avoided 🗡️
-- (CHALLENGING_10_WINS itself), ⚔️ (CHALLENGING_FIRST_WIN — same family),
-- 🧠 (GOOD_FOLD_FIRST — also a thinking-player coded glyph but already
-- claimed). Achievement CHALLENGING_10_WINS's own icon stays 🗡️; the
-- pack icon ♟️ is the differentiator that the unlock callout flags as
-- "new cosmetic" without collapsing with the achievement medallion.
--
-- Mode: CHALLENGING_10_WINS carries `mode = AchievementMode.BOTS` so the
-- grant only fires from bot tables. `ClientGrantableAchievements.Default`
-- keeps CHALLENGING_10_WINS in `clientGrantable` (not `serverWitnessed`)
-- because the difficulty counter is local-only — there's no MP sibling
-- to migrate later (Challenging is a bot-difficulty knob, not a table-
-- composition one).
--
-- Follows the V20 / V25 / V27 / V28 / V29 / V30 / V32 / V33 unlock-only-
-- cosmetic pattern:
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
-- Sort order 410 sits directly after the V33 doubler pack (400), so the
-- unlock-only emote packs are visually grouped if the shelf ever surfaces
-- them in catalog order.
--
-- Client wiring lands in the same commit:
--   * `EmojiPackCatalog.PackEmojis` gains `"emotes_tactician" → ♟️🦅🥷🏹`.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"CHALLENGING_10_WINS" → "emotes_tactician"` so the client grant
--     POST resolves to this row.
--   * `cosmeticRewardFor(AchievementId.CHALLENGING_10_WINS)` mirrors the
--     mapping for the in-game unlock callout.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'emotes_tactician', 'chip_offer', 410, '♟️', FALSE, NULL,
    '{"en":"Tactician Emote Pack","es":"Paquete del táctico"}'::jsonb,
    '{"en":"Emote pack · earned","es":"Paquete de emotes · ganado"}'::jsonb,
    '{"en":"Unlocked by winning 10 hands on the Challenging difficulty — the bots play tighter, raise harder, and bluff less. Unlocks ♟️ 🦅 🥷 🏹 — for the seat that didn''t get lucky, just read the table. Send from the in-game emote tray.","es":"Desbloqueado al ganar 10 manos en la dificultad Desafiante — los bots juegan más cerrado, suben más fuerte y van menos de farol. Desbloquea ♟️ 🦅 🥷 🏹 — para el asiento que no tuvo suerte, sino que leyó la mesa. Envíalas desde la bandeja de emotes."}'::jsonb,
    'emotes.tactician', 0, 1, FALSE, TRUE
);
