-- V13: Add `acquisition_source` to inventory.
--
-- Today every inventory row looks the same regardless of *how* the user
-- got the item — chip-redeemed cosmetic, IAP chip-pack, or future
-- achievement/league reward all land via the same `inventory_sync`
-- path. That makes future "Earned" badges on the My Items screen
-- impossible to render without re-deriving acquisition by joining
-- through the achievement / reward tables, which we don't always have.
--
-- The discriminator is two-valued today:
--   'purchased' — bought with chips or platform IAP (the default).
--   'earned'    — granted as an achievement / league-finish reward, or
--                 any other free-grant path. Wired in this slice, but
--                 no caller writes 'earned' yet — that happens when
--                 the achievement-reward inventory grant lands.
--
-- Default 'purchased' is safe for existing rows: until earned-grant
-- paths exist, every row in the table got there via a purchase.

ALTER TABLE inventory
    ADD COLUMN acquisition_source TEXT NOT NULL DEFAULT 'purchased'
    CHECK (acquisition_source IN ('purchased', 'earned'));
