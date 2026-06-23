-- ─────────────────────────────────────────────────────────────────────────────
-- V70: Remove the redundant "Neon Table" table-theme product.
--
-- Product call (2026-06-22 owner playtest, feedback CARDS-18): same reasoning as
-- the Sunset table theme dropped in V64 — a "table theme" and a "felt" are
-- visually identical in V1 (both just recolor the felt), so the `table_neon`
-- table theme earns its keep nowhere. Unlike Sunset it was already unlock-only
-- (V51 set `unlock_only = TRUE`, pulling it from GET /v1/products), so there is
-- no shop row to drop — but the product itself is retired here so it can no
-- longer be granted or equipped at all.
--
-- Append-only DELETE rather than editing the V51 row, so existing dev DBs keep
-- their Flyway checksums. No products FK references this id, so the row deletes
-- cleanly.
-- ─────────────────────────────────────────────────────────────────────────────

DELETE FROM products WHERE id = 'table_neon';
