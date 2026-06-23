-- ─────────────────────────────────────────────────────────────────────────────
-- V68: disclosed-bot subsidy bookkeeping on `table_sessions` (V67).
--
-- A public table where a lone human plays clearly-labelled bots ("play with
-- bots" fallback) pays REAL chips when the human wins — house-funded, because
-- bots hold no wallet. To keep that a budget line and not an exploit, each such
-- session is tagged and the net house-funded winnings are recorded, so the
-- sit-down path can enforce a per-user rolling-24h cap (no new subsidised table
-- once you've drawn down the day's budget).
--
--   subsidized      — this session ran on a subsidised bot table (vs a normal
--                     real-stakes human table). Set at sit-down.
--   subsidy_granted — net chips the house funded on cash-out
--                     (final_stack − buy_in×(1+rebuys), floored at 0). The
--                     authoritative wallet movement still lives in wallet_events;
--                     this column is the per-session subsidy audit + the input to
--                     the daily-cap sum.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE table_sessions
    ADD COLUMN subsidized      BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN subsidy_granted BIGINT  NOT NULL DEFAULT 0
        CONSTRAINT table_sessions_subsidy_granted_non_negative CHECK (subsidy_granted >= 0);

-- The cap sum reads a user's recently-granted subsidy. Partial index keeps it to
-- the handful of rows that actually granted a subsidy.
CREATE INDEX table_sessions_subsidy_by_user
    ON table_sessions (user_id, closed_at) WHERE subsidy_granted > 0;
