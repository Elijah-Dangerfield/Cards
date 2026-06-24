-- ─────────────────────────────────────────────────────────────────────────────
-- V71: Retune the XP Boost — 5-minute window at 1,000 chips.
--
-- Owner directive (2026-06-24): "we need to update the boost to be just 5 mins.
-- 30 mins is insane. 1k chips for 5 mins boost and that's it." The client-side
-- window length lives in XP_BOOST_DEFAULT_DURATION_MS (now 5 min); this aligns
-- the shop catalog row's price + duration copy with it.
--
-- Append-only UPDATE over the V55 seed / V57 rename rather than editing them.
-- GET /v1/products reflects the new price + copy on next deploy after the
-- 5-min catalog cache rolls.
-- ─────────────────────────────────────────────────────────────────────────────

UPDATE products
SET
    cost_chips = 1000,
    subtitle_by_locale = '{"en":"5 minutes","es":"5 minutos"}'::jsonb,
    description_by_locale = '{"en":"Earn double XP on every hand you play for 5 minutes. Activate it from your profile whenever you''re ready — buy more to keep a few on hand.","es":"Gana el doble de XP en cada mano que juegues durante 5 minutos. Actívalo desde tu perfil cuando quieras; compra más para tener algunos a mano."}'::jsonb
WHERE id = 'boost_xp_2x';
