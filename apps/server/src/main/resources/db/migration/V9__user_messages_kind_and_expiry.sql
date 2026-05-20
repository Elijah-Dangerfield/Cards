-- V9: split user_messages into two surfaces (dialog + inbox) and add
-- a server-side TTL.
--
-- Operators tell us at send time whether the message should:
--   - 'dialog' — pop as a modal on the user's next foreground (current behavior, default for back-compat)
--   - 'inbox'  — sit passively in the Notifications screen + bump a bottom-bar badge
--
-- Expiry: nullable TIMESTAMP. NULL = "never expires" (the user keeps
-- seeing it until they ack). Set it for time-sensitive content
-- ("maintenance Sunday 9pm"), leave it null for evergreen ("we credited
-- you 5000 chips"). The unread-fetch endpoint filters `expires_at IS
-- NULL OR expires_at > now()`; the nightly sweep purges acked + expired
-- rows for storage hygiene.
--
-- Rebuilding the unread partial index to keep it tight — once a row is
-- acked it falls out of the index regardless of expiry, but we want the
-- index small whether or not the operator set a TTL.

ALTER TABLE user_messages
    ADD COLUMN kind TEXT NOT NULL DEFAULT 'dialog',
    ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE user_messages
    ADD CONSTRAINT user_messages_kind_valid CHECK (kind IN ('dialog', 'inbox'));
