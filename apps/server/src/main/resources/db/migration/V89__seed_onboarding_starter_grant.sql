-- ─────────────────────────────────────────────────────────────────────────────
-- V89: seed `onboarding.starterGrant` into the app-config value store.
--
-- The onboarding starter-grant reveal renders BEFORE an account exists, so it
-- can't read the wallet — it reads the unauthed `onboarding.starterGrant` value
-- from `GET /v1/app-config`. The client's ConfiguredValue default is a
-- "0 = unknown" sentinel (a real grant is always > 0), so when the server does
-- not provide the value the reveal degrades to a "lands when you reconnect"
-- treatment and never shows a number — even for an online user.
--
-- V75 seeded only the upgrade/social parity set and missed this, so every
-- environment served an onboarding reveal with no chip number. Seed it to match
-- the actual grant (`Wallet.STARTER_GRANT` = 10_000; DatabaseSchemaTest guards
-- the two against drift). ON CONFLICT DO NOTHING so an admin-console override
-- set before this migration ran is preserved.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO app_config_values (path, value_jsonb) VALUES
    ('onboarding.starterGrant', '10000'::jsonb)
ON CONFLICT (path) DO NOTHING;
