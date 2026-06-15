-- V53: Server-authoritative earned achievements.
--
-- Lifts the *earned set* from client-local to server-of-record (Model 2),
-- the achievement counterpart to V52's XP ledger. Achievement *criteria*
-- evaluation + the cross-hand progress counters stay client-local — only the
-- durable "which achievements has this user earned" set syncs, so earned
-- badges survive reinstall / account switch / a new device.
--
-- One row per (user, achievement); the PK is the dedup boundary, so a
-- retried sync or a re-earn on another device collapses to one row
-- (first-write-wins on earned_at). Listing by user_id uses the PK prefix —
-- no extra index needed.
--
-- This is distinct from the existing /v1/me/grants/achievement/{id} endpoint,
-- which maps an achievement to a *cosmetic inventory grant*; that stays. This
-- table is the persistence of the earned set itself.
--
-- FK to auth.users ON DELETE CASCADE per the V11 convention (a Supabase-side
-- delete cascades these rows).

CREATE TABLE achievements_earned (
    user_id         UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    achievement_id  TEXT NOT NULL,
    earned_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, achievement_id)
);
