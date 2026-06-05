-- ---------------------------------------------------------------------------
-- V50: Collapse the chip-pack lineup to a clean 3-tier ladder.
--
-- Tiers (middle = featured / "POPULAR"; chips-per-dollar climbs with size so
-- bigger packs are the better deal):
--
--   $0.99   ->     5,000 chips   chip_pack_small
--   $4.99   ->    30,000 chips   chip_pack_medium   (featured / POPULAR)
--   $14.99  ->   120,000 chips   chip_pack_large    (best value)
--
-- Replaces the prior 5-pack lineup (small/medium/large/mega + flash_sale).
--
-- NOTE: the displayed price is only a *fallback*. Real charges come from the
-- App Store / Play product behind each SKU, so those store products must be
-- configured to $0.99 / $4.99 / $14.99 for the matching SKUs.
--
-- The starter grant (Wallet.STARTER_GRANT = 10,000) is intentionally left
-- unchanged: it sits between the $0.99 and $4.99 packs, so the free welcome
-- gift stays generous while paid packs still read as clear upgrades.
-- ---------------------------------------------------------------------------

DELETE FROM products WHERE kind = 'chip_pack';

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale,
    grants_chips, ios_sku, ios_fallback_price, android_sku, android_fallback_price
) VALUES
(
    'chip_pack_small', 'chip_pack', 100, '🪙', FALSE, NULL,
    '{"en":"Pocket Stack","es":"Pila de bolsillo"}'::jsonb,
    '{"en":"5,000 chips","es":"5.000 fichas"}'::jsonb,
    5000, 'com.cards.iap.chips.small', '$0.99', 'chips_small', '$0.99'
),
(
    'chip_pack_medium', 'chip_pack', 110, '💰', TRUE,
    '{"en":"POPULAR","es":"POPULAR"}'::jsonb,
    '{"en":"Tall Stack","es":"Pila alta"}'::jsonb,
    '{"en":"30,000 chips","es":"30.000 fichas"}'::jsonb,
    30000, 'com.cards.iap.chips.medium', '$4.99', 'chips_medium', '$4.99'
),
(
    'chip_pack_large', 'chip_pack', 120, '🐋', FALSE, NULL,
    '{"en":"Whale Stack","es":"Pila ballena"}'::jsonb,
    '{"en":"120,000 chips","es":"120.000 fichas"}'::jsonb,
    120000, 'com.cards.iap.chips.large', '$14.99', 'chips_large', '$14.99'
);
