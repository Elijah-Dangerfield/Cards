-- ─────────────────────────────────────────────────────────────────────────────
-- V63: Make card backs + felts much cheaper.
--
-- Product call (2026-06-21): card backs and felts are *self-only* cosmetics —
-- nobody else at the table sees your card back, and the felt is low-impact in
-- solo play — so the original premium pricing (card backs 4,000-15,000, felts
-- 1,500-4,000) didn't match the value. Drop both families to genuinely-cheap
-- while preserving the relative tiering so the fancy ones still cost a bit more.
--
-- Append-only: a fresh UPDATE migration rather than editing the seed rows in
-- V5 / V23 / V24 (card backs) and V5 / V7 / V15 (felts). The shop catalog
-- (GET /v1/products) reflects the new prices on next deploy after its 5-min
-- cache rolls.
-- ─────────────────────────────────────────────────────────────────────────────

-- Card backs: 4,000-15,000 → ~500-1,500, tiering preserved.
UPDATE products SET cost_chips = 500  WHERE id = 'cardback_marble';
UPDATE products SET cost_chips = 600  WHERE id = 'cardback_dots';
UPDATE products SET cost_chips = 700  WHERE id IN (
    'cardback_gold',
    'cardback_neon',
    'cardback_skulls',
    'cardback_fire',
    'cardback_lattice',
    'cardback_pinstripes'
);
UPDATE products SET cost_chips = 800  WHERE id IN ('cardback_hatching', 'cardback_crosshatch');
UPDATE products SET cost_chips = 1000 WHERE id = 'cardback_galaxy';
UPDATE products SET cost_chips = 1200 WHERE id = 'cardback_avatar';
UPDATE products SET cost_chips = 1400 WHERE id = 'cardback_diamond';
UPDATE products SET cost_chips = 1500 WHERE id = 'cardback_holographic';

-- Felts: 1,500-4,000 → ~250-750, tiering preserved.
UPDATE products SET cost_chips = 250 WHERE id IN (
    'felt_royal_red',
    'felt_midnight_blue',
    'felt_pine_green'
);
UPDATE products SET cost_chips = 400 WHERE id = 'felt_charcoal';
UPDATE products SET cost_chips = 750 WHERE id = 'felt_sunset_weekend';
