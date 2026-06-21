-- V62: Recently-played-with — the server-of-record for "the humans you've
-- shared a multiplayer hand with", behind the Home recently-played-with shelf
-- and the friend-request gate (you may only friend someone this surfaced).
--
-- One *directed* row per (viewer, opponent): `user_id`'s view of `opponent_id`.
-- Both directions are written when a hand finishes, so each user's shelf is a
-- plain `WHERE user_id = me ORDER BY last_played_at DESC` with no canonicalising.
-- `last_played_at` is bumped on every shared hand, so the same pair stays a
-- single row and natural recency ordering falls out of the timestamp.
--
-- Only humans are ever recorded — the writer (GameSessionRegistry) sources the
-- pair set from the hand's per-human outcome map, which excludes bots upstream.
--
-- FK to auth.users ON DELETE CASCADE per the V11 convention: a Supabase-side
-- delete cascades a user's rows from both columns. The app-level DELETE /v1/me
-- cascade also clears them explicitly (both directions) so a mid-cascade crash
-- can't strand the opposite-direction row.
--
-- CHECK rejects a self-pair (a user never appears in their own shelf); the
-- writer already skips it, the constraint makes an application bug fail loudly.

CREATE TABLE recently_played_with (
    user_id        UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    opponent_id    UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    last_played_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, opponent_id),
    CHECK (user_id <> opponent_id)
);

-- The shelf reads a single user's rows most-recent-first; the composite index
-- serves both the equality filter and the ordering without a sort.
CREATE INDEX recently_played_with_recency_idx
    ON recently_played_with (user_id, last_played_at DESC);
