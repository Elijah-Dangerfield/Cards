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
     * The one-time Home welcome dialog. Shows once per account ([welcomeSeen]
     * gate) whenever it has something to say: a brand-new account still owed its
     * starter-grant reveal, or an open founding-member window — so existing early
     * players get thanked once too, not just fresh sign-ups.
     *
     * [grantReveal] carries the starter-grant reveal (or null when there's
     * nothing to reveal), and [isFounding] layers the founding-member copy on top.
     */
    data class Welcome(
        val displayName: String,
        val avatarEmoji: String,
        val avatarBackgroundColorHex: String?,
        val grantReveal: GrantReveal?,
        val isFounding: Boolean,
    ) : Blocking {

        /** The starter-grant reveal the welcome dialog makes, when it makes one. */
        sealed interface GrantReveal {
            /** A trustworthy figure to animate — "starting you with 10,000 chips". */
            data class Exact(val chips: Long) : GrantReveal

            /**
             * We couldn't pin a number (offline / pre-config, grant not yet
             * landed) — promise the chips are on their way rather than animate a
             * wrong or zero figure.
             */
            data object Pending : GrantReveal
        }
    }

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
     * Achievements earned during a real-chip multiplayer game that had no
     * at-table reveal (the hand finished on the felt, not in a dialog), banked at
     * hand-end and replayed here so the player still learns what they earned
     * (PROG-13). Carries the [AchievementId] names to reconstruct the celebration
     * from the registry. Drained (the persisted queue cleared) once presented.
     */
    data class AchievementsEarned(
        val achievementIds: List<String>,
    ) : Blocking

    /**
     * One-time "your play style is unlocked" milestone, fired the first time the
     * user crosses the play-style sample threshold.
     */
    data object PlayStyleUnlocked : Blocking

    /**
     * The balance can't cover the cheapest standard buy-in and the ways back
     * haven't been pointed at this episode. Lowest priority of the blocking
     * band — a celebration that itself grants chips (level-up) resolves first
     * and may lift the balance back over the line before this ever fires.
     */
    data class OutOfChips(
        val balance: Long,
        val casualBuyIn: Long,
    ) : Blocking

    /**
     * A newer app version is on the store and worth mentioning. **Lowest
     * priority of the blocking band, below even out-of-chips** — nothing here
     * is time-sensitive, and it should only ever take a Home slot that no
     * celebration, milestone or shortfall wanted.
     *
     * The "worth mentioning" rule lives in
     * [com.dangerfield.cards.libraries.cards.isWorthPromptingFrom]: newer,
     * a feature bump rather than a patch, and not already asked about.
     */
    data class UpdateAvailable(
        val latestVersion: String,
    ) : Blocking

    /**
     * The chip odometer should roll [from] → [to]. Ambient — it plays alongside
     * a blocking celebration rather than blocking on it.
     */
    data class ChipDelta(
        val from: Long,
        val to: Long,
    ) : Ambient
}
