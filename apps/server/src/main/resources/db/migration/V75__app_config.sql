-- ─────────────────────────────────────────────────────────────────────────────
-- V75: `app_config_values` — DB-backed remote config base values.
--
-- Replaces the hardcoded `InMemoryAppConfigSource` (which baked the override
-- tree into the binary, so every flag flip was a redeploy). Each row is one
-- entry in the override tree the client fetches at `GET /v1/app-config`, keyed
-- by the dotted `ConfiguredValue.path` the client declares (e.g.
-- `social.enabled`, `upgrade.minSupportedVersionCode`).
--
-- `value_jsonb` holds the *base* value served when no targeting rule matches —
-- a JSON scalar (`1`, `"off"`, `false`) or, for composite values, a JSON
-- object/array. Per-flag targeting rules + the change audit log land in V76;
-- this migration is just the value store so flags flip with no redeploy.
--
-- Anything absent from this table falls back to the client-side default
-- declared on the `ConfiguredValue` — an empty table is a fully functional
-- app. The seed below mirrors the old in-memory tree so behaviour is
-- unchanged on first deploy and the admin UI has rows to show.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE app_config_values (
    path        TEXT        PRIMARY KEY,
    value_jsonb JSONB       NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Parity seed: the exact tree InMemoryAppConfigSource used to return.
INSERT INTO app_config_values (path, value_jsonb) VALUES
    ('upgrade.minSupportedVersionCode', '1'::jsonb),
    ('upgrade.maintenanceMode',         '"off"'::jsonb),
    ('upgrade.maintenanceMessage',      '"We''re updating the servers, back in a moment."'::jsonb),
    ('social.enabled',                  'false'::jsonb);
