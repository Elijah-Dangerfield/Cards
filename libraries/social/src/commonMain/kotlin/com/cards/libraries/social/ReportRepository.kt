package com.dangerfield.cards.libraries.social

/**
 * Files player reports from the caller's side (MOD-1). A report is fire-and-
 * forget from the client's point of view: the server records it for a human
 * moderator to review later. There is no inbox and no state to observe — the
 * only feedback is the one-shot result of filing.
 *
 * Reporting is always available on a human opponent, independent of the social
 * graph feature flag: it's a store-compliance requirement, not a social nicety.
 */
interface ReportRepository {

    /**
     * Report [userId], optionally tagged with the [roomCode] it happened in, the
     * reporter's selected reason [categories] (canonical keys like `harassment` /
     * `cheating`, empty when none picked), and their optional free-text [reason]
     * (MOD-2). Callers only offer this on another human, so a self-report can't
     * originate here.
     */
    suspend fun reportPlayer(
        userId: String,
        roomCode: String?,
        reason: String?,
        categories: List<String> = emptyList(),
    ): ReportPlayerResult
}

/** Outcome of [ReportRepository.reportPlayer]. */
sealed interface ReportPlayerResult {
    /** The report was filed. */
    data object Reported : ReportPlayerResult

    /** `429` — the report rate limit tripped; try again later. */
    data object RateLimited : ReportPlayerResult

    /** Network failure or any unmapped error. */
    data class Error(val cause: Throwable) : ReportPlayerResult
}
