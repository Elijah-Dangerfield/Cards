-- ─────────────────────────────────────────────────────────────────────────────
-- V66: room visibility — discovery + join policy for public matchmaking.
--
-- Additive column on the V65 `rooms` table. 'private' (the default, backfilled
-- onto every existing row) is the pre-matchmaking behaviour: joinable only by
-- code. 'open' is a human-hosted room the creator opted into matchmaking
-- discovery for; 'public' is a matchmaker-created table with a synthetic system
-- host, dealt by the server. Only 'open' + 'public' are matchmaking-eligible.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE rooms
    ADD COLUMN visibility TEXT NOT NULL DEFAULT 'private'
        CHECK (visibility IN ('private', 'open', 'public'));

-- Discovery scans filter to matchmaking-eligible, joinable rooms; index the
-- columns that scan keys on so it doesn't sequential-scan every room.
CREATE INDEX rooms_visibility_status_idx ON rooms (visibility, status);
