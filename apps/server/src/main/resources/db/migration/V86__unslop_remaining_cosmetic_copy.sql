-- V86: Retire the last four em dashes in shop/reward copy.
--
-- The ENG-3 unslop pass (V78 shop-visible products, V79 unlock-only rewards)
-- rewrote most cosmetic descriptions but never covered four early rows:
-- felt_default (V16), emoji_pack_starter (V18), title_pot_magnet (V19), and
-- cardback_comeback_kid (V20). Each still connected a clause with a spaced em
-- dash, the same tell V78/V79 removed everywhere else; a player flagged one of
-- them (Sentry CARDS-70). This pass finishes the job with the exact same two
-- edits those migrations made: the em dash becomes ordinary punctuation, and
-- the "Equip from your items." UI-affordance tail is dropped (V78's reasoning).
-- Every factual claim is preserved verbatim, including each unlock criterion.
--
-- Append-only UPDATEs over the existing rows so dev Flyway checksums stay
-- intact. Both dev and prod redeploy on push to main and Flyway applies this to
-- each (see decisions.md 2026-07: "Flyway then applies V78/V79" to both live
-- DBs), so no separate credentialed prod write is needed.

UPDATE products
SET description_by_locale = '{"en":"The classic table you start on. Visible to you only in solo games."}'::jsonb
WHERE id = 'felt_default';

UPDATE products
SET description_by_locale = '{"en":"Unlocks 👋 👍 🎉 😀, friendly reactions to get you started. Send them from the in-game emote tray."}'::jsonb
WHERE id = 'emoji_pack_starter';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by sitting at a pot 25× the big blind. Shows under your name at the table, a quiet flex that isn''t for sale."}'::jsonb
WHERE id = 'title_pot_magnet';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by dropping to 10 big blinds or fewer and building back to 100. A phoenix-tone card back."}'::jsonb
WHERE id = 'cardback_comeback_kid';
