-- V7: Realign felt copy with the felts-are-private decision.
--
-- Per docs/decisions.md 2026-05-20: table felts are owner-only, never
-- broadcast to other seats. The V5 seed for `felt_royal_red` said
-- "visible to you only in solo games", which implied the opposite
-- model (public in MP). Replace the description so the shop tile
-- reads cleanly under the new "your table" framing.

UPDATE products
SET description_by_locale = '{"en":"Deep red felt for your playing surface. Equip from your items."}'::jsonb
WHERE id = 'felt_royal_red';
