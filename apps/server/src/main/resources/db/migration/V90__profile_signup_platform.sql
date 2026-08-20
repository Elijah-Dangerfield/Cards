-- V90: Signup platform on profiles.
--
-- The all-time user count and the growth-by-platform graph were install-based
-- (Loki `app.launch` records), so they capped at ~30d of log retention and
-- folded in emulator / side-load noise. `profiles` rows persist forever and
-- are noise-light, but carried no platform, so they couldn't answer
-- "how many users, on which OS, as of when".
--
-- **Stamped once at INSERT and never updated.** This is signup-platform, an
-- immutable cohort dimension, not "the platform this user was last seen on".
-- A lazy backfill (`UPDATE … WHERE platform IS NULL`) was the tempting cheap
-- option and is wrong twice over: a user who signed up on Android and later
-- switched to iOS would rewrite their own historical bar, and because dormant
-- users keep returning, the backfill never finishes — every past bar on the
-- growth graph would keep shifting under the viewer. A stable known-unknown
-- beats a moving answer.
--
-- Nullable, therefore, is permanent for pre-V90 rows rather than a gap waiting
-- to be filled. Dashboards group NULL as "unknown (pre-V90)"; that bucket is a
-- fixed historical set that can never grow.
--
-- 'other' is distinct from NULL on purpose: NULL means we never captured the
-- value, 'other' means a client told us something we don't recognise. The
-- second is a live signal that something is sending a bad `X-Platform`; the
-- first can only ever be history.

ALTER TABLE profiles
    ADD COLUMN platform TEXT NULL;

ALTER TABLE profiles
    ADD CONSTRAINT profiles_platform_check
    CHECK (platform IS NULL OR platform IN ('android', 'ios', 'other'));

-- The growth graph reads `WHERE created_at <= $x GROUP BY platform`, so it
-- scans by time and buckets by platform. Composite in that order serves both
-- the range scan and the grouping.
CREATE INDEX profiles_created_at_platform_idx
    ON profiles (created_at, platform);
