-- V2: Equipment.
--
-- Per-user, per-product equipped state. One row per (user_id, product_id);
-- the row's mere presence means the user has that product equipped.
-- Unequipping deletes the row. This is the simplest model that supports
-- last-write-wins reconciliation: the client tells us "I want this
-- product equipped" (upsert) or "I want it unequipped" (delete), and
-- whichever change has the later updated_at wins.
--
-- No FK to profiles(user_id) for the same reason as the inventory tables
-- and per the V1 migration's stance: profiles is created lazily on first
-- `/v1/me` and we want equipment writes to be possible before that. The
-- application layer enforces the link via the Supabase JWT check.

CREATE TABLE equipment (
    user_id     UUID NOT NULL,
    product_id  TEXT NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, product_id)
);

-- Fast lookup of "what does this user have equipped" — the common read
-- pattern from `/v1/equipment/sync`. Equipment changes are infrequent
-- so the index cost on writes is negligible.
CREATE INDEX equipment_user_id_idx ON equipment (user_id);
