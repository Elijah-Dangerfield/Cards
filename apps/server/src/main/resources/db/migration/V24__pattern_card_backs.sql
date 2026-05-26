-- ─────────────────────────────────────────────────────────────────────────────
-- V24: Seed five Canvas-pattern card backs.
--
-- V23 shipped five gradient + glyph card backs (Skulls, Galaxy, Holographic,
-- Fire, Avatar). This wave adds five hand-drawn pattern backs rendered with
-- Compose Canvas — flat field + repeating motif on top — to round out the
-- catalog with classic textile-style designs:
--
--   * cardback_lattice    — Bicycle-red field with a white diamond grid.
--   * cardback_hatching   — charcoal-black field with parallel gold diagonals.
--   * cardback_crosshatch — emerald field with an orthogonal tan grid.
--   * cardback_dots       — deep-navy field with an offset cream dot grid.
--   * cardback_pinstripes — charcoal field with fine off-white verticals.
--
-- Pricing follows the V5 / V23 band (~4500–6000 chips, unlock 5–8).
-- Sort orders 600–640 continue the card-back shelf (V5: 500–530, V20: 540,
-- V23: 550–590) so the shop UI groups them with the other card backs.
--
-- Client wiring lands in the same commit:
--   * CardBackStyle.{Lattice,Hatching,Crosshatch,Dots,Pinstripes} enum values.
--   * paletteFor branches setting the flat field color + border accent.
--   * Per-style Canvas painters in PlayingCard.kt
--     (LatticePattern / HatchingPattern / CrosshatchPattern /
--     DotsPattern / PinstripesPattern) wired through CardBackOrnament.
--   * cardBackForProductId("cardback_<name>") = CardBackStyle.<Name>
--     mapper entries.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES
(
    'cardback_lattice', 'chip_offer', 600, '♦️', FALSE,
    '{"en":"CLASSIC","es":"CLÁSICO"}'::jsonb,
    '{"en":"Lattice Card Back","es":"Reverso celosía"}'::jsonb,
    '{"en":"Card back","es":"Reverso de carta"}'::jsonb,
    '{"en":"Bicycle-red field with a white diamond lattice. Classic poker-night look. Equip from your items."}'::jsonb,
    'cardback.lattice', 5000, 5, TRUE, FALSE
),
(
    'cardback_hatching', 'chip_offer', 610, '〰️', FALSE, NULL,
    '{"en":"Hatching Card Back","es":"Reverso rayado"}'::jsonb,
    '{"en":"Card back","es":"Reverso de carta"}'::jsonb,
    '{"en":"Charcoal-black field with parallel gold diagonals — art-deco menu energy. Equip from your items."}'::jsonb,
    'cardback.hatching', 5500, 6, TRUE, FALSE
),
(
    'cardback_crosshatch', 'chip_offer', 620, '#️⃣', FALSE, NULL,
    '{"en":"Crosshatch Card Back","es":"Reverso cuadriculado"}'::jsonb,
    '{"en":"Card back","es":"Reverso de carta"}'::jsonb,
    '{"en":"Emerald field with an orthogonal tan grid — billiards-room feel. Equip from your items."}'::jsonb,
    'cardback.crosshatch', 5500, 6, TRUE, FALSE
),
(
    'cardback_dots', 'chip_offer', 630, '⚪', FALSE, NULL,
    '{"en":"Dot Grid Card Back","es":"Reverso de puntos"}'::jsonb,
    '{"en":"Card back","es":"Reverso de carta"}'::jsonb,
    '{"en":"Deep-navy field with an offset cream dot grid. Clean, modern. Equip from your items."}'::jsonb,
    'cardback.dots', 4500, 5, TRUE, FALSE
),
(
    'cardback_pinstripes', 'chip_offer', 640, '🪡', FALSE, NULL,
    '{"en":"Pinstripes Card Back","es":"Reverso de rayas"}'::jsonb,
    '{"en":"Card back","es":"Reverso de carta"}'::jsonb,
    '{"en":"Charcoal field with fine off-white vertical pinstripes — the tailored-suit card. Equip from your items."}'::jsonb,
    'cardback.pinstripes', 5000, 6, TRUE, FALSE
);
