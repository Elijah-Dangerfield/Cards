-- ─────────────────────────────────────────────────────────────────────────────
-- V57: Rename the shop product "2× XP Boost" → "XP Boost".
--
-- The "2×" prefix in the title read as confusing next to the "double XP"
-- description, so the product is now just "XP Boost" (the multiplier stays 2×
-- internally — see XP_BOOST_MULTIPLIER — it's only dropped from the name). The
-- description still spells out the doubling, and the copy is refreshed for the
-- new own-then-activate flow: buying stashes an inactive boost the player lights
-- on demand, rather than starting the timer immediately.
--
-- V55 is already applied, so we UPDATE the existing row rather than editing it.
-- ─────────────────────────────────────────────────────────────────────────────

UPDATE products
SET
    title_by_locale = '{"en":"XP Boost","es":"Boost de XP"}'::jsonb,
    description_by_locale = '{"en":"Earn double XP on every hand you play for 30 minutes. Activate it from your profile whenever you''re ready — buy more to keep a few on hand.","es":"Gana el doble de XP en cada mano que juegues durante 30 minutos. Actívalo desde tu perfil cuando quieras; compra más para tener algunos a mano."}'::jsonb
WHERE id = 'boost_xp_2x';
