package com.dangerfield.cards.libraries.rooms

/**
 * Public matchmaking — the client surface for "Find a table".
 *
 * Honest-by-design: [findTable] only ever seats the user with **real humans**
 * (or opens a fresh, empty public table and genuinely waits) — it never seats a
 * bot. The disclosed-bot fallback is strictly opt-in via [playBots], offered
 * only after a fruitless search and only with the user's explicit consent.
 *
 * Both calls hand back a [Room] the user is now a member of; the caller opens
 * its live socket through [RoomRepository.connect] exactly like a private room,
 * and reuses all the existing room / reconnect / gameplay infrastructure.
 */
interface MatchmakingRepository {

    /**
     * Ask the server to seat the user into the best eligible public/open table
     * whose buy-in falls in [minBuyIn]..[maxBuyIn], else open a fresh public
     * table for them (seated alone, no bots — we genuinely wait for humans).
     */
    suspend fun findTable(minBuyIn: Long, maxBuyIn: Long): FindTableOutcome

    /**
     * Read-only browse: the qualifying tables in [minBuyIn]..[maxBuyIn] the user
     * could join, ordered most-real-humans-first, **seating no one and creating
     * nothing**. Powers the chooser where the user picks which table to join (via
     * [RoomRepository.joinRoom] + [RoomRepository.connect]) instead of being
     * auto-seated by [findTable]. An empty list means no match — the caller offers
     * the disclosed-bot fallback exactly as the empty-search path does.
     */
    suspend fun findCandidates(minBuyIn: Long, maxBuyIn: Long): CandidatesOutcome

    /**
     * Consent to the honest disclosed-bot fallback after the search came up
     * empty: fill the user's own forming public table with **labelled** bots and
     * deal. Valid only on the user's own public table while it's still forming.
     *
     * [PlayBotsOutcome.RealPlayerJoined] is the happy surprise — a human arrived
     * during the search, so the server kept the real game and refused to bot-fill.
     * The client should drop the bot offer and stay in the real table.
     */
    suspend fun playBots(code: String): PlayBotsOutcome

    /**
     * Read the caller's house-funded disclosed-bot subsidy draw-down for the
     * current rolling window. Read before offering the bot fallback so a player
     * near their daily cap is told the limit up front instead of discovering it
     * from a surprising balance afterward (MP-6). Idempotent.
     */
    suspend fun subsidyBudget(): SubsidyBudgetOutcome
}

sealed interface SubsidyBudgetOutcome {
    /**
     * The subsidy budget for the rolling window. [remaining] is clamped to >= 0;
     * 0 means the next subsidized bot table earns no house-funded bonus.
     */
    data class Success(val grantedToday: Long, val cap: Long, val remaining: Long) : SubsidyBudgetOutcome

    data class NotSignedIn(val cause: Throwable? = null) : SubsidyBudgetOutcome
    data class NetworkError(val cause: Throwable) : SubsidyBudgetOutcome
    data class Unknown(val cause: Throwable) : SubsidyBudgetOutcome
}

sealed interface CandidatesOutcome {
    /** The qualifying tables (possibly empty — no match → offer bots). */
    data class Success(val rooms: List<Room>) : CandidatesOutcome

    /** The buy-in range was malformed or outside the allowed band (400). */
    data class InvalidRange(val message: String) : CandidatesOutcome

    /** The top of the requested range is more than the user's wallet holds (400). */
    data class InsufficientBalance(val message: String) : CandidatesOutcome
    data class NotSignedIn(val cause: Throwable? = null) : CandidatesOutcome

    /** Searched too often too fast (429) — back off and retry. */
    data class RateLimited(val cause: Throwable? = null) : CandidatesOutcome
    data class NetworkError(val cause: Throwable) : CandidatesOutcome
    data class Unknown(val cause: Throwable) : CandidatesOutcome
}

sealed interface FindTableOutcome {
    /**
     * Seated into a table. [created] is true when the matchmaker opened a fresh
     * table (no eligible one existed → you're the first one here, waiting for
     * humans), false when it seated you alongside players already there.
     */
    data class Success(val room: Room, val created: Boolean) : FindTableOutcome

    /** The buy-in range was malformed or outside the allowed band (400). */
    data class InvalidRange(val message: String) : FindTableOutcome

    /** The top of the requested range is more than the user's wallet holds (400). */
    data class InsufficientBalance(val message: String) : FindTableOutcome
    data class NotSignedIn(val cause: Throwable? = null) : FindTableOutcome

    /** Searched too often too fast (429) — back off and retry. */
    data class RateLimited(val cause: Throwable? = null) : FindTableOutcome
    data class NetworkError(val cause: Throwable) : FindTableOutcome
    data class Unknown(val cause: Throwable) : FindTableOutcome
}

sealed interface PlayBotsOutcome {
    /** Disclosed bots seated; the table is dealing. */
    data class Success(val room: Room) : PlayBotsOutcome

    /**
     * A real human joined during the search, so the server kept the real game
     * rather than bot-filling. Good news — stay in the table, drop the bot offer.
     */
    data object RealPlayerJoined : PlayBotsOutcome
    data object RoomNotFound : PlayBotsOutcome
    data class NotSignedIn(val cause: Throwable? = null) : PlayBotsOutcome
    data class NetworkError(val cause: Throwable) : PlayBotsOutcome
    data class Unknown(val cause: Throwable) : PlayBotsOutcome
}
