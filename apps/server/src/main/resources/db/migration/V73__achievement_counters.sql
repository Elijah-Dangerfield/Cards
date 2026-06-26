-- V73: Server-authoritative achievement counters (PROG-1).
--
-- V72 graduated the eight headline player stats to the server. Achievement
-- *progress* (max pot seen, all-ins, double/triple-ups, comebacks, good folds,
-- hand-strength shows, per-bot wins, …) still lived only on the device, so it
-- reset on reinstall / account switch — the bars read zero on a second device.
--
-- This makes the server the authority for every achievement counter. Two parts:
--
--  1. The per-hand event grows from a thin signal into the *complete raw facts*
--     of the hand (stacks, blinds, pot, all-in, busts dealt, hand strength
--     shown, …). The ledger is now genuinely event-sourced: any achievement
--     added later can be back-filled by re-folding a player's history. New
--     columns are nullable / defaulted so existing rows and older clients (which
--     don't send the richer facts yet) keep applying cleanly.
--
--  2. The aggregate gains `achievement_counters` — the materialized projection
--     of those facts, a keyed `name -> value` map (JSONB, like per_bot_wins).
--     The single fold that produces it (libraries:achievements) runs identically
--     on client and server, so a reinstalled client can never clobber a
--     server-held counter: it sends no new facts for already-applied hands.
--
-- The streak no longer needs a client-computed snapshot — it's derived by the
-- ordered fold from the `busted` fact (reconstructed as `no_bust_streak = 0`
-- for older events). `no_bust_streak` stays for back-compat but is no longer
-- the authority.

ALTER TABLE user_player_stats
    ADD COLUMN achievement_counters JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE player_stat_events
    ADD COLUMN busted                  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN start_stack             BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN end_stack               BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN big_blind               BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN pot_total               BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN was_all_in              BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN won_by_fold             BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN busts_dealt             INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN folded_would_have_lost  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN hand_strength_shown     TEXT,
    ADD COLUMN bot_difficulty          TEXT;
