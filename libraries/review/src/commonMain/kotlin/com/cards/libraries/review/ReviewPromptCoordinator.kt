package com.dangerfield.cards.libraries.review

/**
 * Decides whether and when to ask the OS to show a native app-store
 * review prompt. Feature code calls [requestPrompt] at a known
 * positive moment (achievement unlock, level-up, clean session end);
 * the coordinator owns the eligibility gate so callers don't
 * re-derive it.
 *
 * Eligibility lives on the client only — install age, last-prompt
 * date, trigger throttling — there is no server round-trip. The OS
 * itself imposes a hard ceiling (≈3 prompts/year on iOS, similar
 * pacing on Android) and silently no-ops past it; the coordinator
 * adds the in-app guard rails on top so we don't burn our annual
 * quota on a player who just installed.
 *
 * Never write a self-built rating dialog as a fallback. If the OS
 * declines to surface the prompt, that's the system working as
 * designed — see product-spec.md §2.6.
 */
interface ReviewPromptCoordinator {

    /**
     * Notify the coordinator that a positive moment has occurred.
     * Returns whether the coordinator chose to forward the request
     * to the platform launcher (true) or suppressed it (false). The
     * platform itself may still decline to show anything; "true"
     * only means we tried.
     */
    suspend fun requestPrompt(trigger: ReviewTrigger): Boolean
}

/**
 * Reasons a [ReviewPromptCoordinator] gets invoked. The coordinator
 * may throttle differently per trigger in the future; today they all
 * funnel through the same eligibility gate.
 */
enum class ReviewTrigger {
    /** A rare/legendary achievement just unlocked. Strong positive signal. */
    AchievementUnlocked,

    /** The user just levelled up. Mid-strength positive signal. */
    LevelUp,

    /** The user won a real-chip multiplayer match — the strongest positive signal. */
    MultiplayerWin,

    /** The user finished a clean play session (no rage-quit). Weak positive signal. */
    SessionEnd,
}
