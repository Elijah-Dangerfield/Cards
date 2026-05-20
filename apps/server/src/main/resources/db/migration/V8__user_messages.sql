-- V8: User-facing in-app messages.
--
-- "Hey, we credited 5000 chips to your account" / "Maintenance Sunday
-- 9pm" / "Welcome back" — anything we want to show as a one-time
-- dialog the next time the user opens the app. Authored by an admin
-- (today via the GitHub Actions workflow that calls
-- POST /v1/admin/messages), delivered via GET /v1/me/messages on
-- foreground, acknowledged via POST /v1/me/messages/{id}/ack.
--
-- One row per (target_user, message). We do NOT broadcast — a "send
-- to everyone" call writes N rows, one per recipient. That keeps the
-- read-path query trivial ("WHERE user_id = ? AND acked_at IS NULL"),
-- the ack-path race-free (per-row update), and demographics targeting
-- a future change to the *write* side without touching the read side.
--
-- `idempotency_key` lets the admin write path retry safely: a flaky
-- network on the operator's side won't double-send. The
-- `(user_id, idempotency_key)` uniqueness is enforced by a partial
-- index instead of the PK so the PK can stay on `id` — we look up
-- messages by their UUID id on the ack path.
--
-- `deep_link` is a nullable client-route string (e.g. "cards://shop"
-- or a feature-route URI). When NULL the dialog's CTA button is a
-- plain dismiss; when set the button navigates and acks atomically.
-- The client owns route resolution; the server just stores the string.
--
-- Same FK-omission rationale as wallets/profiles/inventory: Supabase
-- owns auth.users.

CREATE TABLE user_messages (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL,
    idempotency_key TEXT NOT NULL,
    emoji           TEXT,
    title           TEXT NOT NULL,
    body            TEXT NOT NULL,
    deep_link       TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    acked_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT user_messages_title_non_empty CHECK (length(title) > 0),
    CONSTRAINT user_messages_body_non_empty  CHECK (length(body) > 0)
);

-- Dedup boundary for the admin write path. Same key from the same
-- author re-targeting the same user collapses to a single row.
CREATE UNIQUE INDEX user_messages_user_idempotency_uq
    ON user_messages (user_id, idempotency_key);

-- Unread-fetch is the hot path: "what dialogs should this user see
-- right now?" runs on every foreground for every active user. The
-- partial index keeps the index tiny — once a row is acked it falls
-- out of the index and never costs anything again.
CREATE INDEX user_messages_user_unread_idx
    ON user_messages (user_id, created_at)
    WHERE acked_at IS NULL;
