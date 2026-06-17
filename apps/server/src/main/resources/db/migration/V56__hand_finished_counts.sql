-- V56: Server-witnessed per-user finished-hand counter.
--
-- The authoritative hand loop (GameSession) now runs server-side, so the
-- server can witness — rather than trust the client for — how many hands a
-- user has actually finished. This append-only ledger is that witness: one
-- row per (user, finished hand). Multiplayer achievement gating reads the
-- count off this table so a client can't self-grant an MP achievement it
-- didn't earn (see ClientGrantableAchievements.serverWitnessed).
--
-- The `(user_id, idempotency_key)` primary key is the dedup boundary —
-- key shape is `<sessionId>:<handNumber>:<userId>`, so a hand whose
-- completion is observed more than once (e.g. a snapshot replay after a
-- server restart) collapses to a single row. Listing/counting by user_id
-- uses the PK prefix, so no extra index is needed.
--
-- FK to auth.users ON DELETE CASCADE per the V11 convention: a Supabase-side
-- delete cascades these rows. (Testcontainers seeds the auth.users stub via
-- init-auth.sql, same as wallets / xp_events.)

CREATE TABLE hand_finished_events (
    user_id          UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    idempotency_key  TEXT NOT NULL,
    hand_session_id  UUID NOT NULL,
    hand_number      INTEGER NOT NULL,
    finished_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, idempotency_key)
);
