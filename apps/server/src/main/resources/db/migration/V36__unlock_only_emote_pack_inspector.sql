-- ─────────────────────────────────────────────────────────────────────────────
-- V36: Seed `emotes_inspector` — first of the five BEAT_*_10 per-bot signature
-- emote packs, paired with `AchievementId.BEAT_JANE_10` ("Past the gatekeeper",
-- RARE, BOTS mode, icon 🧐).
--
-- Continues the [docs/todo.md §A "Catalog gating — unlock-only vs purchasable"
-- — More earnable cosmetic pairings] sweep. The V34 footer flagged BEAT_*_10
-- as "rejected — needs five new packs in one ship to avoid an inconsistent
-- half-rollout." This row ships the first of five (V36–V40, in registry
-- order: Jane → David → Gina → Steve → Mike). All five land in one commit so
-- a user who's already at 8/10 vs Jane and at 0/10 vs the others sees a
-- consistent catalog regardless of which BEAT_*_10 they cross first.
--
-- Pairing rationale: BEAT_JANE_10 captures the player who has beaten Jane the
-- gatekeeper 10 times. Jane plays tight and folds skeptically — the moment of
-- mastery against her is "I read your line, I called your bluff, I caught
-- your fold." The "Inspector" archetype emotes from that winner's frame:
-- scrutiny, noting the tell, correcting the read. Pack glyphs are 🔍 📋 🤓 ☝️:
--   * 🔍 magnifying glass — pack icon. "I see right through you" — most
--     directly maps to the gatekeeper-analyst archetype Jane embodies on the
--     loss side. Reads distinctly from her medallion 🧐 (the player wears
--     the inspector hat, the bot wears the monocle — same family, different
--     vantage).
--   * 📋 clipboard — "noted that." The receipt for a read that landed.
--   * 🤓 nerd face — analytical mode, "I had the spreadsheet on you."
--   * ☝️ index pointing up — corrective, "ah ah ah." The micro-gloat that
--     belongs to the player who out-analyzed the analyst.
--
-- Pack icon ☝️ vs achievement medallion 🧐: 🔍 reads as the player's tool
-- (the inspection glyph), 🧐 reads as the subject (the gatekeeper themselves)
-- — distinct enough that the unlock callout doesn't collapse the two glyphs.
--
-- Emoji set is `🔍 📋 🤓 ☝️` — none overlap with the twelve prior packs
-- (drama 💃🧂🎭🤦, cute 🥺🥰😇🤗, fierce 😤🔥💀😎, royal 👑🃏♠️♥️,
-- eliminator 🪦⚰️👻🥀, baller 💸💎🤑📈, iron_stack 🛡️🧱🗿🦾, convincer
-- 🪄🎩😏🤫, disciplined 🧘🦉👁️🪞, grinder ☕⛏️🛠️⌛, doubler 🚀⏫🎯💰,
-- tactician ♟️🦅🥷🏹) so the available-blast pool grows by four real options
-- when the pack lands in inventory. None collide with the four sibling packs
-- in V37–V40 (showstopper 🎤✨👏🎬, outsmarter 💡🪤🕸️🔮, marathoner 🦥🐌🪨🌅,
-- tamer 🦁🎪🤹🪅). None collide with any active achievement medallion icon
-- in the registry — explicitly checked against the full medallion set
-- including 🧐 (BEAT_JANE_10 itself).
--
-- Mode: BEAT_JANE_10 carries `mode = AchievementMode.BOTS` so the grant only
-- fires from bot tables. `ClientGrantableAchievements.Default` keeps
-- BEAT_JANE_10 in `clientGrantable` (not `serverWitnessed`) because the
-- per-bot win counter is local-only — there's no MP sibling to migrate later
-- (Jane is a bot personality, not a table-composition outcome).
--
-- Follows the V25 / V27 / V28 / V29 / V30 / V32 / V33 / V34 unlock-only-
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
-- Sort order 420 sits directly after the V34 tactician pack (410), with the
-- four sibling BEAT_*_10 packs at 430–460 keeping the unlock-only emote
-- shelf visually grouped.
--
-- Client wiring lands in the same commit:
--   * `EmojiPackCatalog.PackEmojis` gains `"emotes_inspector" → 🔍📋🤓☝️`.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"BEAT_JANE_10" → "emotes_inspector"` so the client grant POST
--     resolves to this row.
--   * `cosmeticRewardFor(AchievementId.BEAT_JANE_10)` mirrors the mapping
--     for the in-game unlock callout.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'emotes_inspector', 'chip_offer', 420, '🔍', FALSE, NULL,
    '{"en":"Inspector Emote Pack","es":"Paquete del inspector"}'::jsonb,
    '{"en":"Emote pack · earned","es":"Paquete de emotes · ganado"}'::jsonb,
    '{"en":"Unlocked by beating Jane 10 times — the gatekeeper folds for a reason, and you read it. Unlocks 🔍 📋 🤓 ☝️ — for the seat that had the spreadsheet on you. Send from the in-game emote tray.","es":"Desbloqueado al ganarle a Jane 10 veces — el portero se retira por una razón, y la leíste. Desbloquea 🔍 📋 🤓 ☝️ — para el asiento que tenía la hoja de cálculo sobre ti. Envíalas desde la bandeja de emotes."}'::jsonb,
    'emotes.inspector', 0, 1, FALSE, TRUE
);
