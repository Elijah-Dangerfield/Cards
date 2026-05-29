-- ─────────────────────────────────────────────────────────────────────────────
-- V37: Seed `emotes_showstopper` — second of the five BEAT_*_10 per-bot
-- signature emote packs, paired with `AchievementId.BEAT_DAVID_10`
-- ("Out-bluffed the bluffer", RARE, BOTS mode, icon 😎).
--
-- Continues the V36-V40 BEAT_*_10 signature-pack ship; see V36's header for
-- the "all five at once" rationale.
--
-- Pairing rationale: BEAT_DAVID_10 captures the player who has beaten David
-- the bluffer 10 times. David plays loud — he raises the river, he
-- represents the flush, he tries to make you fold to the story he's
-- telling. The moment of mastery against him is "I called your bluff, and
-- I made it look easy." The "Showstopper" archetype emotes from that
-- winner's frame: drop the mic, take the bow, end the scene. Pack glyphs
-- are 🎤 ✨ 👏 🎬:
--   * 🎤 microphone — pack icon. Mic drop. Most directly maps to the
--     "out-performed the performer" archetype. Reads distinctly from
--     David's medallion 😎 (the bot is the cool guy; the player who beat
--     him gets the literal mic).
--   * ✨ sparkles — your moment. The post-call glitter.
--   * 👏 clapping hands — slow clap, "good show." The respectful version
--     of the gloat — you out-acted the actor.
--   * 🎬 clapperboard — "scene." End-of-act marker, "and that's a wrap."
--
-- Pack icon 🎤 vs achievement medallion 😎: the medallion 😎 also lives in
-- the purchasable `emotes_fierce` pack — explicitly noted in V34 — but the
-- showstopper *pack icon* 🎤 is distinct from both, so the unlock callout
-- (medallion + pack-icon side-by-side) doesn't collide.
--
-- Emoji set is `🎤 ✨ 👏 🎬` — none overlap with the twelve prior packs or
-- the four sibling BEAT_*_10 packs (inspector 🔍📋🤓☝️, outsmarter
-- 💡🪤🕸️🔮, marathoner 🦥🐌🪨🌅, tamer 🦁🎪🤹🪅). ✨ explicitly distinct
-- from the achievement icon 🌟 (a similar but different glyph; 🌟 is in
-- the registry and was rejected to avoid collision).
--
-- Mode: BEAT_DAVID_10 carries `mode = AchievementMode.BOTS` so the grant
-- only fires from bot tables. `ClientGrantableAchievements.Default` keeps
-- BEAT_DAVID_10 in `clientGrantable` (not `serverWitnessed`) for the same
-- reason as Jane — the per-bot win counter is local-only.
--
-- Follows the V25 / V27 / V28 / V29 / V30 / V32 / V33 / V34 / V36 unlock-
-- only-cosmetic pattern.
--
-- Sort order 430 sits directly after V36's inspector pack (420).
--
-- Client wiring lands in the same commit:
--   * `EmojiPackCatalog.PackEmojis` gains `"emotes_showstopper" → 🎤✨👏🎬`.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"BEAT_DAVID_10" → "emotes_showstopper"`.
--   * `cosmeticRewardFor(AchievementId.BEAT_DAVID_10)` mirrors the mapping
--     for the in-game unlock callout.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'emotes_showstopper', 'chip_offer', 430, '🎤', FALSE, NULL,
    '{"en":"Showstopper Emote Pack","es":"Paquete del cierra-show"}'::jsonb,
    '{"en":"Emote pack · earned","es":"Paquete de emotes · ganado"}'::jsonb,
    '{"en":"Unlocked by beating David 10 times — the bluffer ran the script and you outshowed the show. Unlocks 🎤 ✨ 👏 🎬 — for the seat that took the mic. Send from the in-game emote tray.","es":"Desbloqueado al ganarle a David 10 veces — el farolero recitó el guion y tú robaste el espectáculo. Desbloquea 🎤 ✨ 👏 🎬 — para el asiento que tomó el micrófono. Envíalas desde la bandeja de emotes."}'::jsonb,
    'emotes.showstopper', 0, 1, FALSE, TRUE
);
