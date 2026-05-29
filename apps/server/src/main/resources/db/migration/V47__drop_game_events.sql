-- ─────────────────────────────────────────────────────────────────────────────
-- V47: drop `game_events`.
--
-- V31 provisioned the durable event log for the event-sourced multiplayer
-- direction explored 2026-05-27. The architecture flipped 2026-05-29 to
-- snapshot-only state (see docs/decisions.md "2026-05-29 — Multiplayer:
-- snapshot-only state, OTel for debugging"); nothing reads from this table
-- and the write path is being torn out alongside this migration.
--
-- The B0 snapshot table (`room_sessions(state_jsonb)`) lands in its own
-- migration. The `GameEventEnvelope` kotlinx carrier survives in
-- :libraries:gameplay — useful as an in-memory shape if a future "rolling
-- event tail" surface (parked in §B5) wants it.
-- ─────────────────────────────────────────────────────────────────────────────

DROP INDEX IF EXISTS game_events_session_idx;
DROP TABLE IF EXISTS game_events;
