-- V85: Player reports — the trust-and-safety table behind the in-app
-- "Report a player" action (MOD-1). Google Play's UGC policy requires a
-- report path for apps with user-visible content (display names + emotes
-- among strangers in public rooms); this is where those reports land.
--
-- One row per report. A reporter can file more than one report against the
-- same user (e.g. across different rooms), so there is no unique constraint
-- on the pair; the rate limit on the route is what bounds abuse. `room_code`
-- and `reason` are nullable: the report entry point is room-scoped today but
-- the column doesn't force it, and V1 has no reason picker so the reason is
-- captured only when a client sends one.
--
-- FK to auth.users ON DELETE CASCADE per the V11 convention: deleting either
-- account (via Supabase or the DELETE /v1/me cascade) drops the reports it is
-- a party to. There is no pairing invariant to strand, so unlike
-- friend_relations the app layer does not also clear these explicitly.
--
-- CHECK forbids self-reports at the DB level so an application bug can't
-- persist one; the route rejects them with 400 first.
--
-- No auto-ban and no moderation-review UI in V1 — a human reads these later.
-- The (reported_user_id, created_at DESC) index is for that future review
-- ("show me the recent reports against this user").

CREATE TABLE player_reports (
    id                BIGSERIAL PRIMARY KEY,
    reporter_user_id  UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    reported_user_id  UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    room_code         TEXT,
    reason            TEXT,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT player_reports_no_self CHECK (reporter_user_id <> reported_user_id)
);

CREATE INDEX player_reports_reported_idx
    ON player_reports (reported_user_id, created_at DESC);
