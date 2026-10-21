-- V91: Turn the recently-played-with shelf into a head-to-head record.
--
-- `recently_played_with` (V62) already holds one directed row per (viewer,
-- opponent), bumped on every shared finished hand. That is the only social loop
-- in the app that demonstrably works: half of all human pairings have played
-- together more than once, and eleven pairs have shared six or more sessions,
-- while public matchmaking matched 6 searches out of 618 over the same window.
-- Nothing in the product acknowledged those repeat pairings existed.
--
-- So the pair row carries the scoreline too, rather than standing up a second
-- ledger over the same event. The writer is the same all-pairs loop in
-- GameSessionRegistry that already bumps `last_played_at`, so a rivalry costs no
-- extra round trip per hand.
--
-- hands_together      every finished hand the two of them were both dealt into.
-- hands_won           hands the VIEWER took while this opponent was at the table.
-- opponent_hands_won  the mirror. Stored rather than read from the opposite
--                     direction's row, because a card renders from one row and a
--                     join per rivalry for a number we already have is waste.
--                     Both rows are written in the same transaction-per-pair
--                     loop, so they do not drift.
-- biggest_pot_won     largest pot the viewer took with this opponent present.
--                     The one bragging-rights number the hand outcome already
--                     carries (`potTotal`).
-- sessions_together   distinct shared tables, which is the unit a human counts
--                     in ("we've played three times"), not hands.
-- last_session_id     how sessions_together is counted without a second table:
--                     the hand that changes this id is the one that increments
--                     the session count. A hand replayed after a server restart
--                     carries the same session id, so it cannot double-count.
--
-- Existing rows default to zero, so a pair that played before this migration
-- reads as having no record yet rather than a fabricated one. The read path
-- requires at least two shared sessions anyway: one table together is a game,
-- not a rivalry.

ALTER TABLE recently_played_with
    ADD COLUMN hands_together     INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN hands_won          INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN opponent_hands_won INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN biggest_pot_won    BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN sessions_together  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_session_id    UUID;

-- The rivalry read is "my pairs, most recent first, with enough history to
-- count". The PK already covers the user_id prefix; this index keeps the
-- recency ordering off the viewer's rows out of a sort.
CREATE INDEX IF NOT EXISTS recently_played_with_rivalry_idx
    ON recently_played_with (user_id, last_played_at DESC);
