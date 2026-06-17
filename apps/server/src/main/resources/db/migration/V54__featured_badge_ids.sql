-- V54: Featured badges on profiles.
--
-- The owner picks up to 3 earned achievements to feature on their Player
-- Card (the public identity others see at the table / on the profile view).
-- Stored as a JSON array of achievement-id strings in a single TEXT column —
-- the set is tiny (capped at 3), read and written whole, and never queried
-- by element, so a normalized child table would be overkill. Application
-- layer (MeRoutes) owns the cap + de-dup; the column just holds the blob.
--
-- Nullable: NULL = "the user has never chosen," which the client renders as
-- their most-recently-earned badges by default.

ALTER TABLE profiles
    ADD COLUMN featured_badge_ids TEXT;
