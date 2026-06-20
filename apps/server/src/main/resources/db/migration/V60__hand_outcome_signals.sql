-- V60: Enrich the finished-hand ledger with per-hand outcome signals.
--
-- The server already writes one row per (user, finished hand) into
-- hand_finished_events (V56). The per-hand-shape MP achievements that fire
-- once (FIRST_BUST_DEALT_MP, DOUBLE_UP_MP, …) read a single hand's outcome,
-- but the *cumulative* ones — BUST_DEALT_5_MP (five busts dealt across a
-- career) and WIN_BY_FOLD_10_MP (ten pots taken without a showdown) — need a
-- durable per-user tally. Rather than stand up a second ledger we tag each
-- finished-hand row with its outcome shape, so the cumulative tally is a plain
-- SUM / COUNT over the same rows the count already reads.
--
-- busts_dealt: opponents this player busted in this hand (0 for most hands).
-- won_by_fold: this player took the pot without reaching showdown.
--
-- The (user_id, idempotency_key) PK still dedups replays, so a re-observed
-- hand-completion never double-counts a bust or a fold-win.

ALTER TABLE hand_finished_events
    ADD COLUMN busts_dealt INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN won_by_fold BOOLEAN NOT NULL DEFAULT FALSE;
