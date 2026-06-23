-- V69: Server-authoritative play-style aggregation.
--
-- The client derives the four play-style axes (Tight / Aggro / Bluff /
-- Patient) for a player from real gameplay. It records one compact row per
-- hand into a local outbox and flushes batches here; the server sums the raw
-- counters into a per-user rolling aggregate and computes the 0..1 axes at
-- read time. Keeping the count → axis formula server-side means it can be
-- re-tuned without an app release (see docs/decisions.md).
--
-- Same Model-2 shape as V52 xp_progression / V6 wallets: the append-only
-- `play_style_events` table is keyed `(user_id, idempotency_key)` so a retry
-- or reinstall re-flush collapses to a single row, and the aggregate is the
-- authoritative read surface. The aggregate is also the **public** read used
-- by the Opponent Style Reader utility — it carries only summary counts, no
-- per-hand history.
--
-- Both tables FK `user_id` to auth.users(id) ON DELETE CASCADE, matching the
-- V11 convention: a Supabase-side delete cascades the user's play-style rows.

CREATE TABLE user_play_style_aggregate (
    user_id                 UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    -- Denominators. `hands_dealt` is every hand the player was dealt into;
    -- `hands_dealt_non_blind` excludes hands where they only posted a blind
    -- (so VPIP / fold-discipline aren't skewed by forced money).
    hands_dealt             BIGINT NOT NULL DEFAULT 0,
    hands_dealt_non_blind   BIGINT NOT NULL DEFAULT 0,
    -- Numerators for the four axes.
    vpip_count              BIGINT NOT NULL DEFAULT 0,
    pfr_count               BIGINT NOT NULL DEFAULT 0,
    preflop_fold_count      BIGINT NOT NULL DEFAULT 0,
    aggressive_action_count BIGINT NOT NULL DEFAULT 0,
    call_action_count       BIGINT NOT NULL DEFAULT 0,
    showdown_count          BIGINT NOT NULL DEFAULT 0,
    showdown_bluff_count    BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT user_play_style_counts_non_negative CHECK (
        hands_dealt >= 0 AND hands_dealt_non_blind >= 0 AND
        vpip_count >= 0 AND pfr_count >= 0 AND preflop_fold_count >= 0 AND
        aggressive_action_count >= 0 AND call_action_count >= 0 AND
        showdown_count >= 0 AND showdown_bluff_count >= 0
    )
);

-- Append-only ledger of per-hand play-style contributions. One row per hand
-- the player finished. `(user_id, idempotency_key)` is the dedup boundary —
-- the key is `style_<handId>` (or a client UUID) so a re-flush is a no-op.
CREATE TABLE play_style_events (
    user_id                 UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    idempotency_key         TEXT NOT NULL,
    mode                    TEXT NOT NULL,
    in_blind                BOOLEAN NOT NULL,
    vpip                    BOOLEAN NOT NULL,
    pfr                     BOOLEAN NOT NULL,
    preflop_fold            BOOLEAN NOT NULL,
    aggressive_action_count INTEGER NOT NULL,
    call_action_count       INTEGER NOT NULL,
    went_to_showdown        BOOLEAN NOT NULL,
    showdown_bluff          BOOLEAN NOT NULL,
    applied_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, idempotency_key)
);

CREATE INDEX play_style_events_user_applied_idx
    ON play_style_events (user_id, applied_at DESC);
