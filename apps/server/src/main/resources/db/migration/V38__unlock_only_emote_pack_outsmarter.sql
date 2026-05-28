-- ─────────────────────────────────────────────────────────────────────────────
-- V38: Seed `emotes_outsmarter` — third of the five BEAT_*_10 per-bot
-- signature emote packs, paired with `AchievementId.BEAT_GINA_10`
-- ("Beat the fox", RARE, BOTS mode, icon 🦊).
--
-- Continues the V36-V40 BEAT_*_10 signature-pack ship; see V36's header for
-- the "all five at once" rationale.
--
-- Pairing rationale: BEAT_GINA_10 captures the player who has beaten Gina
-- the fox 10 times. Gina plays sly — she sets traps, she slow-plays the
-- nuts, she lures you into the second-best hand. The moment of mastery
-- against her is "I saw the trap, I walked around it, I caught you in
-- yours." The "Outsmarter" archetype emotes from that winner's frame:
-- pattern recognition, trap-spring, seeing-it-coming. Pack glyphs are
-- 💡 🪤 🕸️ 🔮:
--   * 💡 lightbulb — pack icon. The recognition moment, "I see what you're
--     doing." Distinct from Gina's medallion 🦊 (bot = the trickster;
--     player = the one who reads the trick).
--   * 🪤 mousetrap — "I set this up three streets ago." The reverse-trap.
--   * 🕸️ spider web — "caught in my web." Patience-based capture, the
--     player as the patient predator rather than the patient prey.
--   * 🔮 crystal ball — "saw it coming." Future-vision, the gloat for the
--     read that landed.
--
-- 🧠 was the natural first pick for this archetype but is a registered
-- achievement medallion icon in the registry — explicitly avoided here, and
-- 💡 takes its place in the same conceptual slot.
--
-- Emoji set is `💡 🪤 🕸️ 🔮` — none overlap with the twelve prior packs or
-- the four sibling BEAT_*_10 packs (inspector 🔍📋🤓☝️, showstopper
-- 🎤✨👏🎬, marathoner 🦥🐌🪨🌅, tamer 🦁🎪🤹🪅).
--
-- Mode: BEAT_GINA_10 carries `mode = AchievementMode.BOTS` so the grant
-- only fires from bot tables. `ClientGrantableAchievements.Default` keeps
-- BEAT_GINA_10 in `clientGrantable` (not `serverWitnessed`) for the same
-- reason as the others — per-bot counter, local-only.
--
-- Follows the V25-V37 unlock-only-cosmetic pattern.
--
-- Sort order 440 sits directly after V37's showstopper pack (430).
--
-- Client wiring lands in the same commit:
--   * `EmojiPackCatalog.PackEmojis` gains `"emotes_outsmarter" → 💡🪤🕸️🔮`.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"BEAT_GINA_10" → "emotes_outsmarter"`.
--   * `cosmeticRewardFor(AchievementId.BEAT_GINA_10)` mirrors the mapping
--     for the in-game unlock callout.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'emotes_outsmarter', 'chip_offer', 440, '💡', FALSE, NULL,
    '{"en":"Outsmarter Emote Pack","es":"Paquete del listillo"}'::jsonb,
    '{"en":"Emote pack · earned","es":"Paquete de emotes · ganado"}'::jsonb,
    '{"en":"Unlocked by beating Gina 10 times — the fox sets traps and you walked around every one. Unlocks 💡 🪤 🕸️ 🔮 — for the seat that saw it coming. Send from the in-game emote tray.","es":"Desbloqueado al ganarle a Gina 10 veces — la zorra tiende trampas y tú esquivaste cada una. Desbloquea 💡 🪤 🕸️ 🔮 — para el asiento que lo vio venir. Envíalas desde la bandeja de emotes."}'::jsonb,
    'emotes.outsmarter', 0, 1, FALSE, TRUE
);
