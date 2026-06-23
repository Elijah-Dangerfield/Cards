-- ─────────────────────────────────────────────────────────────────────────────
-- V65: durable room registry — `rooms` + `room_members` (todo.md §B2).
--
-- Until now rooms lived only in memory (InMemoryRoomService): a server restart
-- dropped every lobby's membership, so reconnecting players found their room
-- gone even though the in-progress hand was recoverable from `room_sessions`
-- (B0). These two tables make the registry durable. InMemoryRoomService becomes
-- a write-through, lazily-hydrated cache in front of them: create / join / leave
-- write here before responding, and a lookup for a code that isn't in memory
-- hydrates from here.
--
-- Coupling notes:
--   * `host_user_id` is NOT FK'd to auth.users. Most rooms are hosted by a real
--     user, but public matchmaker-created tables (Phase 2) use a synthetic
--     system host id that has no auth.users row. Same deliberate loose coupling
--     as `room_sessions.room_code` — the link is enforced at the app layer.
--   * `room_sessions.room_code` already exists (V48) as the per-room game-state
--     snapshot; it stays a separate table keyed by the same code. A room can
--     exist in `rooms` with no `room_sessions` row (lobby, no hand dealt yet).
--   * Bots are members too. They never reconnect and carry no auth identity, so
--     their seat is captured by the `bot_*` columns; a NULL `bot_personality`
--     marks a human member. Personality is stored by name and rebuilt from
--     BotPersonality.Roster on hydrate.
--   * `visibility` (private / open / public) is intentionally NOT here — it
--     arrives with matchmaking in a later additive migration, so this slice
--     stays a faithful persistence of today's room model.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE rooms (
    code         TEXT        PRIMARY KEY,
    host_user_id UUID        NOT NULL,
    status       TEXT        NOT NULL CHECK (status IN ('lobby', 'playing', 'finished')),
    buy_in       BIGINT      NOT NULL,
    max_seats    INTEGER     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE room_members (
    room_code               TEXT        NOT NULL REFERENCES rooms(code) ON DELETE CASCADE,
    user_id                 UUID        NOT NULL,
    seat_index              INTEGER     NOT NULL,
    display_name            TEXT        NOT NULL,
    joined_at               TIMESTAMPTZ NOT NULL,
    -- Wall-clock instant the socket dropped (or join time, for a never-connected
    -- member). NULL once connected. Drives the disconnect-grace reaper; persisted
    -- so a restart doesn't reset every seat's grace clock.
    disconnected_at         TIMESTAMPTZ,
    avatar_emoji            TEXT        NOT NULL DEFAULT '',
    avatar_background_color TEXT,
    -- Bot seat columns: all NULL for a human member.
    bot_personality         TEXT,
    bot_difficulty          TEXT,
    bot_revealed            BOOLEAN,
    PRIMARY KEY (room_code, user_id),
    -- One member per seat. Seat allocation already enforces this in memory;
    -- the constraint makes a buggy double-seat fail loudly instead of silently
    -- corrupting the seating chart.
    UNIQUE (room_code, seat_index)
);

-- A room's members are always read as a set keyed by code (hydrate, observe).
CREATE INDEX room_members_room_code_idx ON room_members (room_code);
