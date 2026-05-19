-- V3: Per-user inventory.
--
-- One row per (user, owned product). The mere presence of a row means
-- "this user owns this product" — there's no soft-delete state because
-- ownership in V1 is permanent (refunds revert; nothing else removes a
-- row). `cost_chips_at_purchase` is recorded so a future ledger audit
-- can replay spend; `purchased_at` is the original optimistic write
-- on the client, used as the conflict timestamp for re-syncs.
--
-- Same FK-omission rationale as profiles + equipment: Supabase owns
-- `auth.users` and Testcontainers doesn't have it; the JWT validation
-- in the app layer guarantees the user_id is real.

CREATE TABLE inventory (
    user_id                 UUID NOT NULL,
    product_id              TEXT NOT NULL,
    cost_chips_at_purchase  BIGINT NOT NULL DEFAULT 0,
    purchased_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, product_id)
);

CREATE INDEX inventory_user_id_idx ON inventory (user_id);
