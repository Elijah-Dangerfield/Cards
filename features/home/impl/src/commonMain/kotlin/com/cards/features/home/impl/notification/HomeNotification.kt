package com.dangerfield.cards.features.home.impl.notification

import com.dangerfield.cards.libraries.cards.LevelReward

/**
 * The single "when the user lands on Home, show X" moment the arbiter picks per
 * snapshot. Split into two coexisting bands:
 *
 * - [Blocking] — full-screen, mutually exclusive, presented one at a time only
 *   while Home is settled. The arbiter surfaces at most one.
 * - [Ambient] — non-modal reveals (the chip odometer) that coexist with a
 *   blocking notification; they coordinate around it rather than compete.
 *
 * Every previous Home gate that fired its own flag + its own race (welcome
 * dialog, level-up celebration, play-style unlock) now resolves through one
 * pure [GetHomeScreenNotification] pass, so priority + "seed vs real crossing"
 * live in one place instead of five.
 */
sealed interface HomeNotification {

    /** Full-screen, mutually exclusive, queued one at a time. */
    sealed interface Blocking : HomeNotification

    /** Non-modal; coexists with a [Blocking] notification. */
    sealed interface Ambient : HomeNotification

    /**
     * First-run starter-grant reveal. Fires once for a brand-new wallet we
     * didn't already reveal in onboarding.
     */
    data class Welcome(
        val displayName: String,
        val avatarEmoji: String,
        val avatarBackgroundColorHex: String?,
        val chips: Long,
    ) : Blocking

    /**
     * Full-screen level-up celebration for the net [level] reached since the
     * last celebrated level, with the aggregated [rewards] the granter already
     * granted (this is the reveal, not the grant).
     */
    data class LevelUp(
        val level: Int,
        val rewards: List<LevelReward>,
    ) : Blocking

    /**
     * One-time "your play style is unlocked" milestone, fired the first time the
     * user crosses the play-style sample threshold.
     */
    data object PlayStyleUnlocked : Blocking

    /**
     * The chip odometer should roll [from] → [to]. Ambient — it plays alongside
     * a blocking celebration rather than blocking on it.
     */
    data class ChipDelta(
        val from: Long,
        val to: Long,
    ) : Ambient
}
