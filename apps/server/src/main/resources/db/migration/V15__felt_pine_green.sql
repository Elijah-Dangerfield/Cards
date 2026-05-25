-- ─────────────────────────────────────────────────────────────────────────────
-- Adds the Pine Green felt (`felt_pine_green`) to the chip-offer catalog.
--
-- Color is a muted slate-green (#14322B), deliberately not "saturated
-- casino-green" per product-spec §3.1. The client resolves the hex via
-- `feltSurfaceColor(EquippedFelt.PineGreen)` in EquippedFelt.kt; only the
-- catalog row + product-id mapping are server-authoritative here.
--
-- Slotted between the existing felts (royal_red=200, midnight_blue=210,
-- charcoal=220) at sort_order=215 so it groups visually with the cheap
-- gateway felts. Same 1500-chip price + unlock_level=1 as the other
-- entry-tier felts.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable
) VALUES (
    'felt_pine_green', 'chip_offer', 215, '🟢', FALSE, NULL,
    '{"en":"Pine Green Felt","es":"Fieltro verde pino"}'::jsonb,
    '{"en":"Table felt","es":"Fieltro de mesa"}'::jsonb,
    '{"en":"Muted pine-green felt — slate, not casino. Equip from your items — visible to you only in solo games."}'::jsonb,
    'felt.pine_green', 1500, 1, TRUE
);
