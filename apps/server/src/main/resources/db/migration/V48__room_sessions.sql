-- ─────────────────────────────────────────────────────────────────────────────
-- V48: `room_sessions` snapshot table.
--
-- Snapshot-only persistence for the per-room GameSession (see docs/decisions.md
-- "2026-05-29 — Multiplayer: snapshot-only state, OTel for debugging" and
-- docs/todo.md §B0). One row per active session, overwritten inside the
-- per-session mutex on every state mutation. Replaces the in-memory-only
-- StateFlow as the source of truth: a server restart mid-hand hydrates the
-- registry from this table so reconnecting players see their seats/stacks/
-- betting round intact.
--
-- Shape:
--   * `session_id UUID PRIMARY KEY` — stable session identity, generated at
--     GameSession construction and durable for the lifetime of the room.
--   * `room_code TEXT UNIQUE` — six-char room code the registry keys by today.
--     A separate `rooms` table is coming with §B2; until then `room_code` lets
--     us look up "what's the active session for this code?". When B2 lands the
--     column moves to that table and `room_sessions.session_id` FKs to it.
--   * `state_jsonb JSONB` — full kotlinx-serialized `GameState`. Serialization
--     happens at the application layer via the same `kotlinx.serialization.json`
--     Json the wire format uses, so round-trip equivalence is guaranteed by
--     the engine's own tests.
--   * `updated_at TIMESTAMPTZ` — wall-clock for operational visibility.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE room_sessions (
    session_id  UUID         PRIMARY KEY,
    room_code   TEXT         NOT NULL UNIQUE,
    state_jsonb JSONB        NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
