-- V52: Server-authoritative XP / progression.
--
-- Lifts XP/level from client-local (Model 1) to server-of-record
-- (Model 2: optimistic-local + server-reconciled) — the same shape as
-- V6 wallets. The client keeps computing XP per hand with XpCalculator
-- and applies it offline; it flushes the deltas to the server, which
-- sums them into the authoritative `total_xp`. See
-- docs/wiki/state-authority-and-sync.md and docs/decisions.md ("Phase 3
-- migration will lift this to a server-authoritative xp_events table").
--
-- `level` is intentionally NOT stored: the curve (level N = N²×100 XP)
-- is client/content, derived from `total_xp` on read. Storing it would
-- duplicate the formula and risk drift.
--
-- Lifetime hand counters (hands_played/won/folded/...) stay client-local
-- for now — they feed client-side achievement criteria, which also stay
-- local in this slice. They graduate to the server alongside achievements.
--
-- Both tables FK `user_id` to auth.users(id) ON DELETE CASCADE, matching the
-- V11 convention for every per-user table: a Supabase-side delete (dashboard
-- or Admin API from DELETE /v1/me) cascades the user's XP rows automatically,
-- and a user_id with no auth.users row can't be inserted. (Testcontainers
-- seeds the auth.users stub via init-auth.sql, same as wallets.)

CREATE TABLE user_progression (
    user_id     UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    total_xp    BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Defense-in-depth: XP is monotonic and never negative. A trip here
    -- is the strongest signal of an application-layer bug.
    CONSTRAINT user_progression_total_xp_non_negative CHECK (total_xp >= 0)
);


-- Append-only ledger of every XP award applied to a user.
--
-- The `(user_id, idempotency_key)` primary key is the dedup boundary —
-- the client can retry a sync (or reinstall and re-flush its local
-- ledger) without double-counting. Key shape mirrors the source:
-- `hand_<handId>` for per-hand awards, `achievement_<achievementId>`
-- for achievement XP (matching the wallet's `achievement.<id>` reason
-- convention).
--
-- `source` is a short tag from the client (e.g. "hand", "achievement").
-- `mode` is "BOTS" | "MULTIPLAYER". `hand_id` is the originating hand
-- for hand awards, NULL for achievement awards. `delta_xp` is clamped
-- non-negative + capped per-event by the application layer before insert
-- (cheap anti-abuse; XP is play-money/low-stakes in V1).
CREATE TABLE xp_events (
    user_id          UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    idempotency_key  TEXT NOT NULL,
    delta_xp         BIGINT NOT NULL,
    source           TEXT NOT NULL,
    mode             TEXT NOT NULL,
    hand_id          TEXT,
    applied_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, idempotency_key)
);

-- Per-user history reads ("recent XP movements") use the
-- (user_id, applied_at DESC) shape — same as wallet_events.
CREATE INDEX xp_events_user_applied_idx
    ON xp_events (user_id, applied_at DESC);
