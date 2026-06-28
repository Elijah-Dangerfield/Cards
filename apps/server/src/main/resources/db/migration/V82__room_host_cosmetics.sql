-- ─────────────────────────────────────────────────────────────────────────────
-- V82: persist host-chosen table cosmetics on the room.
--
-- SHOP-3. The game creator's equipped felt + card back become the table's look,
-- shown to every player at the table. The host's selection is captured at create
-- time as the felt + card-back catalog product ids and echoed onto every room
-- snapshot; the client maps the ids to a style. The server stores them opaquely
-- and never interprets them.
--
-- Nullable: NULL means the host had nothing equipped in that slot, in which case
-- each client falls back to its own equipped cosmetic. No default — a room
-- without a host override simply stores NULL.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE rooms
    ADD COLUMN felt_product_id      TEXT,
    ADD COLUMN card_back_product_id TEXT;
