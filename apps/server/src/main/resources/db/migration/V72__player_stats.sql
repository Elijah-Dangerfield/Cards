-- V72: Server-authoritative player stats.
--
-- Hand counters (played / won / folded / lost-at-showdown / bot hands), the
-- no-bust streak (current + best), and the per-bot win map have lived only on
-- the device until now, so switching accounts or reinstalling reset them and
-- the stats screen + achievement progress bars read wrong on a second device.
-- This graduates them to the server as the source of truth; achievements
-- become predicates over these counters rather than carrying their own
-- progress numbers.
--
-- Same Model-2 shape as V69 play_style / V6 wallets: the append-only
-- `player_stat_events` table is keyed `(user_id, idempotency_key)` so a retry
-- or reinstall re-flush collapses to a single row, and the aggregate is the
-- authoritative read surface. Both tables FK `user_id` to auth.users(id) ON
-- DELETE CASCADE, matching the V11 convention.
--
-- Streaks are order-dependent, so the per-hand event carries the
-- client-computed no-bust streak snapshot. The aggregate takes the latest
-- applied value as the current streak and the running max as the best — the
-- summable counters accumulate the usual way.

CREATE TABLE user_player_stats (
    user_id                 UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    hands_played            BIGINT NOT NULL DEFAULT 0,
    hands_won               BIGINT NOT NULL DEFAULT 0,
    hands_folded            BIGINT NOT NULL DEFAULT 0,
    hands_lost_at_showdown  BIGINT NOT NULL DEFAULT 0,
    bot_hands_played        BIGINT NOT NULL DEFAULT 0,
    current_no_bust_streak  BIGINT NOT NULL DEFAULT 0,
    best_no_bust_streak     BIGINT NOT NULL DEFAULT 0,
    -- Per-bot win counts, e.g. {"bot_shark": 3}. Read by the per-bot-wins
    -- achievement predicate; small (one key per bot the player has beaten).
    per_bot_wins            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT user_player_stats_counts_non_negative CHECK (
        hands_played >= 0 AND hands_won >= 0 AND hands_folded >= 0 AND
        hands_lost_at_showdown >= 0 AND bot_hands_played >= 0 AND
        current_no_bust_streak >= 0 AND best_no_bust_streak >= 0
    )
);

-- Append-only ledger of per-hand stat contributions. One row per hand the
-- player finished. `(user_id, idempotency_key)` is the dedup boundary — the
-- key is `stat_<handId>` (or a client UUID) so a re-flush is a no-op.
CREATE TABLE player_stat_events (
    user_id                 UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    idempotency_key         TEXT NOT NULL,
    mode                    TEXT NOT NULL,
    won                     BOOLEAN NOT NULL,
    folded                  BOOLEAN NOT NULL,
    lost_at_showdown        BOOLEAN NOT NULL,
    vs_bot                  BOOLEAN NOT NULL,
    -- Bot the player beat this hand, when `won` and `vs_bot`; null otherwise.
    beaten_bot_id           TEXT,
    -- Client-computed no-bust streak after this hand (order-dependent).
    no_bust_streak          BIGINT NOT NULL,
    applied_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, idempotency_key)
);

CREATE INDEX player_stat_events_user_applied_idx
    ON player_stat_events (user_id, applied_at DESC);
