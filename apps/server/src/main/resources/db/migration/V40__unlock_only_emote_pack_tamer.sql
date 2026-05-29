-- ─────────────────────────────────────────────────────────────────────────────
-- V40: Seed `emotes_tamer` — fifth and final of the BEAT_*_10 per-bot
-- signature emote packs, paired with `AchievementId.BEAT_MIKE_10`
-- ("Tamed the maniac", RARE, BOTS mode, icon 🤡).
--
-- Closes the V36-V40 BEAT_*_10 signature-pack ship; see V36's header for
-- the "all five at once" rationale. With this row landing, every
-- BEAT_*_10 achievement in the registry has a cosmetic complement and the
-- "no half-rollout" constraint resolves.
--
-- Pairing rationale: BEAT_MIKE_10 captures the player who has beaten Mike
-- the maniac 10 times. Mike plays loud and chaotic — three-bets light,
-- shoves wide, jams every hand he enters. The moment of mastery against
-- him is "I rode out the chaos, I kept my discipline, I tamed the storm."
-- The "Tamer" archetype emotes from that winner's frame: composure under
-- pressure, mastery-of-chaos, the lion-tamer's chair. Pack glyphs are
-- 🦁 🎪 🤹 🪅:
--   * 🦁 lion — pack icon. The tamed beast. "I rode the chaos." Reads
--     distinct from Mike's medallion 🤡 (bot = the chaos clown; player =
--     the one who tamed it).
--   * 🎪 circus tent — "this is the arena where I tamed you." The
--     stage-set glyph for chaos that the player ran rather than fled.
--   * 🤹 juggler — "I kept the chaos in the air." Composure-under-chaos,
--     not panic.
--   * 🪅 piñata — "I broke the chaos open." Payoff glyph.
--
-- Pack icon 🦁 vs achievement medallion 🤡: both are large animal/character
-- glyphs but distinct enough that the unlock callout (medallion + pack-icon
-- side-by-side) reads cleanly. The conceptual pair (clown tamed by lion) is
-- intentional — same narrative shape as V39's turtle/sloth pairing.
--
-- Emoji set is `🦁 🎪 🤹 🪅` — none overlap with the twelve prior packs or
-- the four sibling BEAT_*_10 packs (inspector 🔍📋🤓☝️, showstopper
-- 🎤✨👏🎬, outsmarter 💡🪤🕸️🔮, marathoner 🦥🐌🪨🌅). 🤡 itself is in the
-- achievement medallion set (BEAT_MIKE_10 itself) and is explicitly avoided.
--
-- Mode: BEAT_MIKE_10 carries `mode = AchievementMode.BOTS` so the grant
-- only fires from bot tables. `ClientGrantableAchievements.Default` keeps
-- BEAT_MIKE_10 in `clientGrantable` (not `serverWitnessed`) for the same
-- reason as the others.
--
-- Follows the V25-V39 unlock-only-cosmetic pattern.
--
-- Sort order 460 sits directly after V39's marathoner pack (450), closing
-- the BEAT_*_10 signature-pack cluster at 420-460.
--
-- Client wiring lands in the same commit:
--   * `EmojiPackCatalog.PackEmojis` gains `"emotes_tamer" → 🦁🎪🤹🪅`.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"BEAT_MIKE_10" → "emotes_tamer"`.
--   * `cosmeticRewardFor(AchievementId.BEAT_MIKE_10)` mirrors the mapping
--     for the in-game unlock callout.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'emotes_tamer', 'chip_offer', 460, '🦁', FALSE, NULL,
    '{"en":"Tamer Emote Pack","es":"Paquete del domador"}'::jsonb,
    '{"en":"Emote pack · earned","es":"Paquete de emotes · ganado"}'::jsonb,
    '{"en":"Unlocked by beating Mike 10 times — the maniac jams everything and you rode out the storm. Unlocks 🦁 🎪 🤹 🪅 — for the seat that tamed the chaos. Send from the in-game emote tray.","es":"Desbloqueado al ganarle a Mike 10 veces — el maníaco apuesta todo y tú aguantaste la tormenta. Desbloquea 🦁 🎪 🤹 🪅 — para el asiento que domó el caos. Envíalas desde la bandeja de emotes."}'::jsonb,
    'emotes.tamer', 0, 1, FALSE, TRUE
);
