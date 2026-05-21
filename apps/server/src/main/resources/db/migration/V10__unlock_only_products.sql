-- V10: `unlock_only` flag for products.
--
-- Per [product-spec.md §4.2](docs/product/product-spec.md#42-the-unlock-only-catalog):
-- some cosmetics (legendary achievement rewards, league-tier rewards,
-- RFT cosmetics, achievement-chain cosmetics) are NEVER purchasable
-- in the shop, ever. The shop catalog and the unlock catalog must be
-- disjoint sets so prestige stays prestige and the no-pay-to-win
-- principle ([§4.5](docs/product/product-spec.md#45-no-pay-to-win--the-hard-rule))
-- holds the moment an achievement-tier cosmetic ships.
--
-- Default FALSE so every row already in the catalog stays shop-eligible
-- — no existing item was meant to be unlock-only. The seed below leaves
-- the column unset on every row; future unlock-only items get a
-- `unlock_only = TRUE` on insert.
--
-- The shop catalog query (`PostgresProductCatalogSource.read`) filters
-- `WHERE unlock_only = FALSE`. Inventory grants for unlock-only items
-- still need to be findable by id at some point (Trophy Case rendering
-- in a future migration), but V1 keeps the unlock catalog empty so the
-- Trophy Case can ship later without a schema change.

ALTER TABLE products
    ADD COLUMN unlock_only BOOLEAN NOT NULL DEFAULT FALSE;
