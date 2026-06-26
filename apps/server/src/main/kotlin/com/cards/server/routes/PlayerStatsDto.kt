package com.dangerfield.cards.server.routes

import kotlinx.serialization.Serializable

/**
 * Wire format for the server-authoritative player-stats endpoints.
 *
 * `GET /v1/me/player-stats` returns [PlayerStatsResponse] (the cumulative
 * counters + streaks + per-bot wins). `POST /v1/me/player-stats/sync` accepts
 * [PlayerStatsSyncRequest] (a batch of per-hand contributions the client wants
 * to flush) and returns [PlayerStatsSyncResponse] with the post-sync snapshot
 * plus per-event outcomes.
 *
 * Mirrors the progression / play-style sync model: "client tells the server
 * what happened locally, server reconciles." The idempotency key is the dedup
 * boundary — replaying it is a no-op ([PlayerStatEventOutcomeDto.AlreadyApplied]),
 * so a flaky network or reinstall re-flush can't double-count a hand.
 */
@Serializable
data class PlayerStatsDto(
    val handsPlayed: Long,
    val handsWon: Long,
    val handsFolded: Long,
    val handsLostAtShowdown: Long,
    val botHandsPlayed: Long,
    val currentNoBustStreak: Long,
    val bestNoBustStreak: Long,
    val perBotWins: Map<String, Long> = emptyMap(),
    /**
     * The full materialized achievement-counter projection (`name -> value`).
     * The client reads its progress bars from this rather than a local cache, so
     * they survive reinstall / account switch. Defaulted for older clients.
     */
    val achievementCounters: Map<String, Long> = emptyMap(),
)

@Serializable
data class PlayerStatsResponse(
    val schemaVersion: Int = 1,
    val stats: PlayerStatsDto,
)

@Serializable
data class PlayerStatsSyncRequest(
    val events: List<PlayerStatHandDto> = emptyList(),
)

/** One finished hand's stat signals, flushed client → server. */
@Serializable
data class PlayerStatHandDto(
    /** Client-derived stable key, e.g. `stat_<handId>`. */
    val idempotencyKey: String,
    /** "BOTS" | "MULTIPLAYER" — tagged for future weighting/filtering. */
    val mode: String,
    val won: Boolean,
    val folded: Boolean,
    val lostAtShowdown: Boolean,
    val vsBot: Boolean,
    /** Bot the player beat this hand, when [won] && [vsBot]; null otherwise. */
    val beatenBotId: String? = null,
    /** Client-computed no-bust streak after this hand; `0` means busted this hand. */
    val noBustStreak: Long,
    // Richer facts (PROG-1) — defaulted so a client that hasn't been updated to
    // send them still applies cleanly; those counters stay zero until it does.
    // [busted] is reconstructed from `noBustStreak == 0` when null, so the
    // no-bust streak stays correct without a client change.
    val busted: Boolean? = null,
    val botDifficulty: String? = null,
    val startStack: Long = 0,
    val endStack: Long = 0,
    val bigBlind: Long = 0,
    val potTotal: Long = 0,
    val wasAllIn: Boolean = false,
    val wonByFold: Boolean = false,
    val bustsDealt: Int = 0,
    val foldedWouldHaveLost: Boolean = false,
    val handStrengthShown: String? = null,
)

@Serializable
data class PlayerStatsSyncResponse(
    val schemaVersion: Int = 1,
    /** Post-sync snapshot, after every event in the batch ran. */
    val stats: PlayerStatsDto,
    val results: List<PlayerStatEventResultDto>,
)

@Serializable
data class PlayerStatEventResultDto(
    val idempotencyKey: String,
    val outcome: PlayerStatEventOutcomeDto,
)

/**
 * Per-event sync outcomes. Additive — clients fall back to [Applied] (most
 * permissive) on an unknown enum value, mirroring progression / play-style.
 */
@Serializable
enum class PlayerStatEventOutcomeDto {
    /** Hand committed for the first time. */
    Applied,

    /** Key was already on the server; this call is a no-op replay. */
    AlreadyApplied,
}
