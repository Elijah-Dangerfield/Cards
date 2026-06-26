-- ─────────────────────────────────────────────────────────────────────────────
-- V74: persist last-known stacks alongside the room_sessions snapshot.
--
-- MP-13. The GameSession keeps a `lastKnownStacks` map — each player's stack as
-- of the last hand they were seated for, retained even after they bust out and
-- are dropped from the deal. The leave / teardown cash-out reads it so a
-- busted-and-dropped player (no live seat) is cashed out their real 0 rather
-- than refunded their whole escrow (a chip mint).
--
-- That map was in-memory only, so after a server crash the boot recovery sweep
-- (DefaultTableSessionRecoverySweep) rehydrated only `state_jsonb` — which has
-- no seat for a busted-dropped player — and fell through to a full-escrow
-- refund: the mint this column closes. We now persist the map so the sweep
-- reads each open session's real last-known stack.
--
-- `{playerId(string) -> stack(long)}`, serialized with the same kotlinx Json the
-- state column uses. DEFAULT '{}' so pre-V74 rows and any insert that omits the
-- column round-trip as an empty map (no recorded last-known stack → the sweep
-- falls back to a full refund, the prior behaviour).
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE room_sessions
    ADD COLUMN last_known_stacks_jsonb JSONB NOT NULL DEFAULT '{}';
