-- ─────────────────────────────────────────────────────────────────────────────
-- V39: Seed `emotes_marathoner` — fourth of the five BEAT_*_10 per-bot
-- signature emote packs, paired with `AchievementId.BEAT_STEVE_10`
-- ("Out-waited the turtle", RARE, BOTS mode, icon 🐢).
--
-- Continues the V36-V40 BEAT_*_10 signature-pack ship; see V36's header for
-- the "all five at once" rationale.
--
-- Pairing rationale: BEAT_STEVE_10 captures the player who has beaten Steve
-- the turtle 10 times. Steve plays slow — long tank-folds, deliberate
-- limps, an opening range so tight you can fold to him for hours. The
-- moment of mastery against him is "I out-grinded the grinder, I outlasted
-- the patient one." The "Marathoner" archetype emotes from that winner's
-- frame: endurance, patience-on-patience, the long-game payoff. Pack glyphs
-- are 🦥 🐌 🪨 🌅:
--   * 🦥 sloth — pack icon. The patient-er-than-you archetype, "I had
--     more patience than you did." Reads distinct from Steve's medallion
--     🐢 (turtle = the slow bot; sloth = the slower player who still
--     beat him). Same conceptual family (slow animals), different glyph.
--   * 🐌 snail — even slower. "I waited you out."
--   * 🪨 rock — "I didn't move." The unmoved-by-pressure flex.
--     Explicitly distinct from `emotes_iron_stack` 🗿 (moai) — 🪨 is the
--     plain rock glyph; the iron-stack moai is heavier and stylized.
--   * 🌅 sunrise — "the long night ended in my favor." Endurance payoff.
--
-- Pack icon 🦥 vs achievement medallion 🐢: both are slow-animal glyphs but
-- distinct enough that the unlock callout (medallion + pack-icon
-- side-by-side) reads as "you out-slowed the turtle" rather than collapsing
-- into one ambiguous glyph. The conceptual pair is intentional — it tells
-- the story of the achievement at a glance.
--
-- Emoji set is `🦥 🐌 🪨 🌅` — none overlap with the twelve prior packs or
-- the four sibling BEAT_*_10 packs (inspector 🔍📋🤓☝️, showstopper
-- 🎤✨👏🎬, outsmarter 💡🪤🕸️🔮, tamer 🦁🎪🤹🪅). 🐢 itself is in the
-- achievement medallion set (BEAT_STEVE_10 itself) and is explicitly avoided
-- here — the pack uses a sibling slow-animal glyph instead so the unlock
-- callout doesn't show the same glyph twice.
--
-- Mode: BEAT_STEVE_10 carries `mode = AchievementMode.BOTS` so the grant
-- only fires from bot tables. `ClientGrantableAchievements.Default` keeps
-- BEAT_STEVE_10 in `clientGrantable` (not `serverWitnessed`) for the same
-- reason as the others.
--
-- Follows the V25-V38 unlock-only-cosmetic pattern.
--
-- Sort order 450 sits directly after V38's outsmarter pack (440).
--
-- Client wiring lands in the same commit:
--   * `EmojiPackCatalog.PackEmojis` gains `"emotes_marathoner" → 🦥🐌🪨🌅`.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"BEAT_STEVE_10" → "emotes_marathoner"`.
--   * `cosmeticRewardFor(AchievementId.BEAT_STEVE_10)` mirrors the mapping
--     for the in-game unlock callout.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'emotes_marathoner', 'chip_offer', 450, '🦥', FALSE, NULL,
    '{"en":"Marathoner Emote Pack","es":"Paquete del maratonista"}'::jsonb,
    '{"en":"Emote pack · earned","es":"Paquete de emotes · ganado"}'::jsonb,
    '{"en":"Unlocked by beating Steve 10 times — the turtle waits forever and you waited longer. Unlocks 🦥 🐌 🪨 🌅 — for the seat that out-grinded the grinder. Send from the in-game emote tray.","es":"Desbloqueado al ganarle a Steve 10 veces — la tortuga espera para siempre y tú esperaste más. Desbloquea 🦥 🐌 🪨 🌅 — para el asiento que aguantó más que el aguantador. Envíalas desde la bandeja de emotes."}'::jsonb,
    'emotes.marathoner', 0, 1, FALSE, TRUE
);
