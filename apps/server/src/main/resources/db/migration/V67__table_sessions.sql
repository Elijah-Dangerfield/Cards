-- ─────────────────────────────────────────────────────────────────────────────
-- V67: `table_sessions` — the durable interlock between a player's in-RAM
-- multiplayer table stack and their persistent chip wallet (V6).
--
-- Authored on `feat/mp-chip-economy` as V60; renumbered to V67 when the escrow
-- ENGINE was salvaged onto `feat/public-matchmaking` (develop already shipped a
-- different V60..V64; matchmaking added V65/V66). The engine lands DORMANT here
-- — the sit-down / cash-out route wiring is Phase 4.
--
-- One row per sit-down at a real-stakes MP table. The buy-in debit, every
-- rebuy debit, and the cash-out credit all land in `wallet_events` (the chip
-- ledger), keyed off this row's `session_id`:
--     table:{session_id}:buyin      delta -buy_in
--     table:{session_id}:rebuy:{n}  delta -buy_in
--     table:{session_id}:cashout    delta +final_stack
-- so a sit always nets correctly and a retried movement collapses to a no-op.
-- This table is lifecycle bookkeeping, NOT a second money ledger — the chips
-- only ever move in `wallet_events`.
--
-- `status` is the crash/disconnect interlock:
--     open    — bought in, seat funded, in play.
--     closing — cash-out credit in flight (forward-only flip from open).
--     closed  — cashed out; terminal.
-- Cash-out reads the row, flips open→closing, credits the wallet (idempotent
-- on the cashout key), then flips closing→closed. Re-running after a crash
-- lands on identical state: the credit is keyed (replay = no-op) and the flips
-- are forward-only, so a boot sweep over non-closed rows can never double-
-- credit or lose chips.
--
-- Same FK-omission rationale as V1–V6: Supabase owns `auth.users` and our
-- Testcontainers Postgres doesn't, so `user_id` is enforced at the app layer
-- via the JWT.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE table_sessions (
    session_id   UUID PRIMARY KEY,
    user_id      UUID NOT NULL,
    room_code    TEXT NOT NULL,
    -- Stake-tier name at open time (audit/display); `buy_in` is the
    -- authoritative debited amount, frozen so a later tier recalibration
    -- can't change what this session owes or refunds.
    stake_tier   TEXT NOT NULL,
    buy_in       BIGINT NOT NULL,
    rebuy_count  INTEGER NOT NULL DEFAULT 0,
    status       TEXT NOT NULL,
    opened_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT table_sessions_buy_in_non_negative CHECK (buy_in >= 0),
    CONSTRAINT table_sessions_rebuy_count_non_negative CHECK (rebuy_count >= 0),
    CONSTRAINT table_sessions_status_valid CHECK (status IN ('open', 'closing', 'closed'))
);

-- Double-spend guard: a user can hold at most ONE non-closed session at a
-- time, so the same wallet chips can't fund two tables simultaneously. The
-- partial unique index enforces it at the DB level — sit-down inserts the row
-- first (claiming this slot), then debits, so a racing second sit is rejected
-- by the index rather than by an application-layer check.
CREATE UNIQUE INDEX table_sessions_one_active_per_user
    ON table_sessions (user_id) WHERE status <> 'closed';

-- Cash-out paths (explicit leave, disconnect reaper, room teardown, boot
-- sweep) fan out over a room's non-closed sessions. Partial index keeps it
-- tight to the live set.
CREATE INDEX table_sessions_active_by_room
    ON table_sessions (room_code) WHERE status <> 'closed';
