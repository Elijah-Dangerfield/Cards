-- ─────────────────────────────────────────────────────────────────────────────
-- V51: Pull the purchasable player titles + the Neon Table out of the shop.
--
-- Product direction: titles aren't something players should *buy* — they read
-- as earned flair shown under your name at the table. The three seeded
-- purchasable titles (`title_bluff_master`, `title_shark`, `title_high_roller`)
-- join the already-unlock-only titles (V17/V35/V41/V42/V44/V45) as earn-only.
-- The Neon Table theme is retired from the storefront outright.
--
-- Follows the established unlock-only pattern:
--   - `unlock_only = TRUE` keeps each row out of the GET /v1/products catalog
--     (`PostgresProductCatalogSource.read` filters `WHERE unlock_only = FALSE`)
--     while remaining reachable via `readById`, so any already-owned copy still
--     renders + equips from My Items / Profile special items.
--   - No achievement grant is wired for these ids yet; they simply leave the
--     shop. A future achievement → title mapping can grant them via the same
--     `clientGrantable` path the other earn-only titles use.
-- ─────────────────────────────────────────────────────────────────────────────

UPDATE products
SET unlock_only = TRUE
WHERE id IN (
    'title_bluff_master',
    'title_shark',
    'title_high_roller',
    'table_neon'
);
