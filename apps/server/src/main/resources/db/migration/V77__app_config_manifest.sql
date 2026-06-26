-- ─────────────────────────────────────────────────────────────────────────────
-- V77: `app_config_manifest` — the in-code config registry captured per app
-- version. One row per `ConfiguredValue` per build: its type, in-code default,
-- and (for enum-like flags) allowed values. CI uploads it at release time so the
-- admin tool can answer "what did 1.0.1 ship with" and show the baseline a remote
-- override would replace. Append-only by version; re-uploading a version replaces
-- that version's rows.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE app_config_manifest (
    version_code         INTEGER     NOT NULL,
    app_version          TEXT,
    path                 TEXT        NOT NULL,
    type                 TEXT        NOT NULL,        -- boolean | int | long | double | string | json
    default_jsonb        JSONB       NOT NULL,
    description          TEXT,
    allowed_values_jsonb JSONB,                       -- enum-like flags; NULL otherwise
    captured_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (version_code, path)
);

CREATE INDEX app_config_manifest_version_idx ON app_config_manifest (version_code DESC);
