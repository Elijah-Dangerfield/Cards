-- ─────────────────────────────────────────────────────────────────────────────
-- V23: Seed five new card backs to expand the cosmetic catalog.
--
-- V5 shipped the original four (`cardback_marble/gold/neon/diamond`) and V20
-- added an unlock-only `cardback_comeback_kid`. This migration adds five
-- purchasable card backs covering a wider visual range — edgy (Skulls),
-- premium animated (Holographic, Galaxy), bold (Fire), and a personal
-- "your-avatar" back (Avatar).
--
-- Pricing follows the V5 band:
--   * 5000 chips / unlock 5 for solid-palette additions (Skulls, Fire).
--   * 7000 chips / unlock 8 for Galaxy (gradient + star overlay).
--   * 15000 chips / unlock 20 for Holographic (animated shimmer overlay
--     — the same premium tier as `cardback_diamond` plus a small
--     surcharge for the live animation).
--   * 10000 chips / unlock 10 for Avatar (personal flex; the only card
--     back where opponents render a fallback because the user's emoji
--     would otherwise leak onto every face-down card).
--
-- Sort orders 550–590 continue the existing card-back shelf (V5: 500–530;
-- V20: 540) so the shop UI groups them together. `is_equippable = TRUE`
-- for all five so My Items can toggle them. `unlock_only = FALSE` keeps
-- them in the shop catalog (contrast with V20 which is unlock-only).
--
-- Client wiring lands in the same commit:
--   * `CardBackStyle.{Skulls,Galaxy,Holographic,Fire,Avatar}` enum
--     values + `paletteFor(...)` palette branches.
--   * Per-style overlay layer in `PlayingCardBack` for Skulls (centered
--     skull glyph), Galaxy (sparkle), Holographic (animated hue strip),
--     and Avatar (the human seat's emoji on their avatar color).
--   * `cardBackForProductId("cardback_<name>") = CardBackStyle.<Name>`
--     entries in the client-side mapper.
--   * Avatar back uses a new `AvatarBackOverlay` param threaded only at
--     the human-seat render sites; non-human render sites pass null so
--     they fall back to the Default look.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES
(
    'cardback_skulls', 'chip_offer', 550, '💀', FALSE, NULL,
    '{"en":"Skulls Card Back","es":"Reverso calaveras"}'::jsonb,
    '{"en":"Card back","es":"Reverso de carta"}'::jsonb,
    '{"en":"Crimson-on-black skull motif for the bold. Equip from your items."}'::jsonb,
    'cardback.skulls', 5000, 5, TRUE, FALSE
),
(
    'cardback_galaxy', 'chip_offer', 560, '✨', FALSE, NULL,
    '{"en":"Galaxy Card Back","es":"Reverso galaxia"}'::jsonb,
    '{"en":"Card back","es":"Reverso de carta"}'::jsonb,
    '{"en":"Indigo-and-magenta gradient with a centered star sparkle. Equip from your items."}'::jsonb,
    'cardback.galaxy', 7000, 8, TRUE, FALSE
),
(
    'cardback_holographic', 'chip_offer', 570, '🌈', TRUE,
    '{"en":"PREMIUM","es":"PREMIUM"}'::jsonb,
    '{"en":"Holographic Card Back","es":"Reverso holográfico"}'::jsonb,
    '{"en":"Card back · Premium","es":"Reverso · Premium"}'::jsonb,
    '{"en":"Animated rainbow shimmer that slowly cycles across the card. Equip from your items."}'::jsonb,
    'cardback.holographic', 15000, 20, TRUE, FALSE
),
(
    'cardback_fire', 'chip_offer', 580, '🔥', FALSE, NULL,
    '{"en":"Fire Card Back","es":"Reverso fuego"}'::jsonb,
    '{"en":"Card back","es":"Reverso de carta"}'::jsonb,
    '{"en":"Black-to-ember vertical gradient for the heaters. Equip from your items."}'::jsonb,
    'cardback.fire', 5000, 5, TRUE, FALSE
),
(
    'cardback_avatar', 'chip_offer', 590, '🪞', FALSE,
    '{"en":"PERSONAL","es":"PERSONAL"}'::jsonb,
    '{"en":"Avatar Card Back","es":"Reverso de avatar"}'::jsonb,
    '{"en":"Card back · Personal","es":"Reverso · Personal"}'::jsonb,
    '{"en":"Your avatar emoji on your avatar color. Renders only on your own hole cards — opponents see a default back. Equip from your items."}'::jsonb,
    'cardback.avatar', 10000, 10, TRUE, FALSE
);
