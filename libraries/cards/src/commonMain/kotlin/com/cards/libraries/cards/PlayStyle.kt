package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.Flow

/**
 * A player's derived play-style, as the four normalized (0..1) radar axes the
 * UI renders. Computed server-side from real gameplay (see
 * `PlayStyleRepository`) and cached locally; never derived on-device, so the
 * count → axis formula stays tunable without an app release.
 *
 * - **tight** — `1 − VPIP`; high = folds a lot preflop.
 * - **aggro** — normalized aggression factor (bets+raises vs calls).
 * - **bluff** — rate of betting into a showdown with a weak hand and losing.
 * - **patient** — preflop fold-discipline (folds when not in a blind).
 */
data class PlayStyleAxes(
    val tight: Float,
    val aggro: Float,
    val bluff: Float,
    val patient: Float,
    /** Hands the axes were computed from. Display is gated on [MIN_SAMPLE]. */
    val sampleSize: Long,
) {
    /** True once enough hands back the axes to show a meaningful shape. */
    val hasEnoughData: Boolean get() = sampleSize >= MIN_SAMPLE

    companion object {
        /**
         * Below this many dealt hands the derived shape is noise — the UI shows
         * a "keep playing to reveal your style" state instead (see docs/decisions.md).
         */
        const val MIN_SAMPLE: Long = 20

        val Empty = PlayStyleAxes(tight = 0f, aggro = 0f, bluff = 0f, patient = 0f, sampleSize = 0)
    }
}

/**
 * The play-style signals for one finished hand, from the human's perspective.
 * The room feature builds this at hand-end from the action stream;
 * [PlayStyleRepository.recordHand] writes it to the outbox to be flushed and
 * folded into the server aggregate.
 */
data class PlayStyleHandSummary(
    val handId: String,
    val mode: XpMode,
    /** True when the player only posted a blind (excluded from VPIP denominators). */
    val inBlind: Boolean,
    /** Voluntarily put money in preflop (call or raise, not a blind). */
    val vpip: Boolean,
    /** Raised preflop. */
    val pfr: Boolean,
    /** Folded preflop. */
    val preflopFold: Boolean,
    /** Bets + raises across the whole hand. */
    val aggressiveActionCount: Int,
    /** Calls across the whole hand. */
    val callActionCount: Int,
    val wentToShowdown: Boolean,
    /** Bet/raised into a showdown holding a weak made hand and lost. */
    val showdownBluff: Boolean,
)

/**
 * Owns the user's derived play-style. Model 2 (optimistic-local +
 * server-reconciled), mirroring [ProgressionRepository]: [recordHand] appends
 * one outbox row per hand offline; [sync] flushes the batch and caches the
 * server's authoritative axes. [getStyleFor] reads another player's public
 * style for the Opponent Style Reader.
 */
interface PlayStyleRepository {

    /** The user's own cached axes; null until the first sync populates them. */
    fun observeOwnStyle(): Flow<PlayStyleAxes?>

    suspend fun getOwnStyle(): PlayStyleAxes?

    /** Append one finished hand's signals to the outbox. */
    suspend fun recordHand(summary: PlayStyleHandSummary)

    /**
     * Flush pending hands to the server and cache the returned axes.
     * Idempotent + single-flight; an empty outbox is a valid hydrate-only call.
     */
    suspend fun sync(): Result<Unit>

    /**
     * Another player's public play-style, for the play-poker player card.
     * Null when they have no recorded style yet. Network-backed (with a small
     * in-session cache); gate the call on `tool_opponent_style` ownership.
     */
    suspend fun getStyleFor(userId: String): Result<PlayStyleAxes?>

    /** Reset all local play-style state. Used by "Fresh Start" / debug menus. */
    suspend fun deleteAll()
}
