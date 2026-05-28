-- ─────────────────────────────────────────────────────────────────────────────
-- V31: `game_events` durable event log + transport-level `seq` mirror.
--
-- First Flyway change in the [docs/todo.md §B0 "Foundation: event log
-- primitives"] migration path. Pairs with the in-memory
-- `GameEventEnvelope(v, type, payload)` carrier shipped last cycle
-- (commit 70ca340 — feat(gameplay): add v1 GameEvent envelope) which is
-- the kotlinx-side companion to the JSONB row written here.
--
-- This migration provisions the durable surface only — no writes land
-- yet. The next B0 sub-item ("Persist `GameSession` events to Postgres
-- inside the per-session mutex") wires the producer; the existing
-- in-memory `MutableSharedFlow<GameEvent>` continues to be the live
-- transport. Decoupling table provisioning from the write path keeps
-- the surface easy to roll back if anything in the persistence model
-- needs rethinking before the second bullet lands.
--
-- Shape rationale:
--   * `session_id UUID` — looks ahead to the B1+ event-sourced session
--     identity. Today `GameSessionRegistry` keys sessions by the 6-char
--     room `code` string; that's a join-table boundary the second B0
--     bullet has to resolve (likely by promoting `GameSession` to a
--     UUID-stamped row when it first writes). Picking UUID now over
--     TEXT keeps the foreign-key shape clean once persisted membership
--     (§B3 — `rooms` + `room_members`) lands.
--   * `seq BIGINT` — monotonic per `session_id`, mirrors
--     `GameEvent.sequence: Long` already on every emitted event. The
--     `(session_id, seq)` composite PK is the ordering invariant the
--     event-sourced replay path leans on (B1 + B2).
--   * `occurred_at TIMESTAMPTZ DEFAULT NOW()` — wall-clock for
--     human-readable audit, not for ordering. Replay uses `seq` only.
--   * `event_type TEXT` — duplicates the `@SerialName` on the kotlinx
--     `GameEvent` variant ("HandStarted", "BlindPosted", "ActionTaken",
--     etc.) so cheap SQL filtering ("show me every HandComplete on
--     session X") works without unmarshalling JSONB.
--   * `event_jsonb JSONB` — full `GameEventEnvelope` round-trip. The
--     envelope itself carries the `v: Int` version discriminator so a
--     future v2 reader can fork without rewriting history. The PRIMARY
--     KEY enforces idempotency on event append.
--
-- Index on `session_id` alone supports the "give me this session's
-- recent tail" replay query (B1 bootstrap, B2 RequestEventsSince). The
-- PK already covers `(session_id, seq)` lookups for snapshot
-- compaction.
--
-- No FK to a `sessions` table yet — the parent table doesn't exist
-- until B0 bullet 2 / B1. Adding the FK with NULL/orphan handling now
-- would just churn when the parent shape lands.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE game_events (
    session_id  UUID         NOT NULL,
    seq         BIGINT       NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    event_type  TEXT         NOT NULL,
    event_jsonb JSONB        NOT NULL,
    PRIMARY KEY (session_id, seq)
);

CREATE INDEX game_events_session_idx ON game_events (session_id);
