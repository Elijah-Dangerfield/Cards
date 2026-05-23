-- V12: Add `is_equippable` to products.
--
-- Today the client decides whether to show an Equip/Unequip button on
-- an owned product by string-matching the productId prefix
-- (felt_*, cardback_*, title_*, tool_*, table_*). That implicit logic is
-- fragile — emote packs and avatar packs fall through the prefix net but
-- still render a meaningless Equip button on the My Items screen.
--
-- The fix is to put the discriminator on the product row itself so the
-- catalog is authoritative. The client mirrors the flag verbatim;
-- equippability stops being a client-side guessing game.
--
-- Default FALSE keeps purchasable IAP rows (chip_pack_*) and unlock-style
-- cosmetics (emote packs, avatar packs) inert. The UPDATE flips the
-- visual-cosmetic and tool rows on by prefix — matches the historical
-- client-side prefix logic, so behavior is preserved for every product
-- that was already wired up.

ALTER TABLE products
    ADD COLUMN is_equippable BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE products
    SET is_equippable = TRUE
    WHERE id LIKE 'felt_%'
        OR id LIKE 'cardback_%'
        OR id LIKE 'title_%'
        OR id LIKE 'table_%'
        OR id LIKE 'tool_%';
