-- ─────────────────────────────────────────────────────────────────────────────
-- V64: Remove the redundant "Sunset Table" table-theme product.
--
-- Product call (2026-06-22 owner playtest, feedback CARDS-18): a "table theme"
-- and a "felt" are visually identical in V1 — both just recolor the felt — so
-- the premium `table_sunset` table theme is redundant with the cheaper
-- `felt_sunset_weekend` felt, which earns the same on-table look. Drop the
-- table theme; the Sunset felt stays.
--
-- Append-only DELETE rather than editing the V5 seed row, so existing dev DBs
-- keep their Flyway checksums. No products FK references this id, so the row
-- deletes cleanly; the shop catalog (GET /v1/products) drops it on next deploy
-- after its cache rolls.
-- ─────────────────────────────────────────────────────────────────────────────

DELETE FROM products WHERE id = 'table_sunset';
