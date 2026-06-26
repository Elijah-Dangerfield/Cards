-- ─────────────────────────────────────────────────────────────────────────────
-- V76: per-flag targeting rules + a change audit log for remote config.
--
-- Builds on V75's `app_config_values` (the base value served when nothing
-- matches). A flag can now carry ordered rules: the server evaluates them in
-- ascending `priority`, first match wins, and falls back to the base value when
-- none match. The endpoint still returns a *resolved* tree — the client model
-- is untouched; targeting + staged rollouts are entirely server-side.
--
-- `conditions_jsonb` is the serialized predicate (see RuleConditions): any of
-- platform set, version-code range, country set, locale set, user-id allow/deny
-- lists, and a 0–100 rollout percentage (deterministic bucketing on
-- hash(userId-or-installId : flagPath)). An empty `{}` predicate is an
-- unconditional override at that priority.
--
-- `app_config_audit` is the append-only trail of every mutation made through
-- the admin API — who/when/what, with the before+after row snapshots for
-- diffing. Never written by the request path, only by admin writes.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE app_config_rules (
    id              UUID         PRIMARY KEY,
    flag_path       TEXT         NOT NULL REFERENCES app_config_values(path) ON DELETE CASCADE,
    priority        INTEGER      NOT NULL,
    value_jsonb     JSONB        NOT NULL,
    conditions_jsonb JSONB       NOT NULL DEFAULT '{}'::jsonb,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    description     TEXT,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- The hot read path groups rules by flag, ordered by priority.
CREATE INDEX app_config_rules_flag_priority_idx
    ON app_config_rules (flag_path, priority);

CREATE TABLE app_config_audit (
    id           UUID         PRIMARY KEY,
    at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor        TEXT         NOT NULL,
    action       TEXT         NOT NULL,
    flag_path    TEXT,
    before_jsonb JSONB,
    after_jsonb  JSONB
);

-- Audit views are "latest first", optionally filtered to one flag.
CREATE INDEX app_config_audit_at_idx ON app_config_audit (at DESC);
CREATE INDEX app_config_audit_flag_idx ON app_config_audit (flag_path);
