-- ─────────────────────────────────────────────────────────────────────────────
-- V58: Carry the XP-boost flag on each ledger row.
--
-- The client already records per-event `was_boosted` locally (an XP boost
-- doubled that hand award). Persisting it server-side lets the flag round-trip
-- through `POST /v1/me/progression/sync` so the recent-XP feed's "⚡ 2×" tag
-- survives a reinstall / account switch, instead of coming back as false.
--
-- Additive + defaulted, so existing rows and older clients (which omit the
-- field) are unaffected — they read/write `false`.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE xp_events ADD COLUMN was_boosted BOOLEAN NOT NULL DEFAULT false;
