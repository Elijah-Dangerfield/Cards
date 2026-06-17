-- ─────────────────────────────────────────────────────────────────────────────
-- V55: Seed the chip-priced 2× XP Boost as a shop product.
--
-- The boost mechanic already ships client-side (XpBoostRepository time-window
-- in AppCache; ProgressionRepositoryImpl doubles hand XP while active). This
-- adds the user-facing *buy* path: a chip_offer whose grants_key the client
-- routes to a consumable purchase (spend chips → XpBoostRepository.activate())
-- instead of the normal inventory redeem.
--
-- It's a CONSUMABLE, so it differs from every other chip_offer:
--   - `is_equippable = FALSE` — there's nothing to equip; the boost is a timer.
--   - No inventory row is ever written for it, so it never shows as "owned"
--     and stays re-buyable (re-buying stacks more time onto the window).
--   - `grants_key = 'boost.xp_2x'` is the client's discriminator (see
--     XP_BOOST_GRANTS_KEY in XpBoost.kt). The id `boost_xp_2x` keys the
--     client's "Boosts" storefront shelf via the product-id prefix convention.
--
-- The client regroups offers into shelves by id prefix, so the numeric
-- sort_order only affects raw catalog order. sort_order=820 slots it after the
-- tools (800-810), which keeps `felt_royal_red` (200) the first cosmetic offer
-- the catalog returns (a server-test contract).
--
-- Price (5,000 chips) is a directional starting point — same gateway tier as a
-- mid chip pack's worth of play — tune freely; it's a single catalog row.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable
) VALUES (
    'boost_xp_2x', 'chip_offer', 820, '⚡', FALSE, NULL,
    '{"en":"2× XP Boost","es":"Boost de 2× XP"}'::jsonb,
    '{"en":"30 minutes","es":"30 minutos"}'::jsonb,
    '{"en":"Earn double XP on every hand for the next 30 minutes. Buy more while it''s running to stack extra time onto the window.","es":"Gana el doble de XP en cada mano durante los próximos 30 minutos. Compra más mientras está activo para acumular tiempo extra."}'::jsonb,
    'boost.xp_2x', 5000, 1, FALSE
);
