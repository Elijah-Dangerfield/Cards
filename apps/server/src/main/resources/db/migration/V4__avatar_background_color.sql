-- V4: Avatar background color.
--
-- Adds a nullable hex-string column for the per-user color shown behind
-- their avatar. NULL = "use the theme default" (the client renders the
-- design system's surface-secondary tone). A small palette enforced
-- server-side keeps the picker honest and prevents palette drift across
-- builds.
--
-- TEXT (not VARCHAR(7)) because Postgres treats them identically and
-- TEXT is more idiomatic; validation lives in the application layer
-- (AvatarPalette) so the database doesn't have to learn the allowed set.

ALTER TABLE profiles
    ADD COLUMN avatar_background_color TEXT;
