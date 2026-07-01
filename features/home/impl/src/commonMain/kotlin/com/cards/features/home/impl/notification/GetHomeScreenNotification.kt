package com.dangerfield.cards.features.home.impl.notification

import com.dangerfield.cards.libraries.cards.LevelReward

/**
 * A snapshot of every persisted fact the Home notification arbiter reasons over,
 * captured at one instant. Lifting the inputs to a value type keeps
 * [GetHomeScreenNotification] a pure function of its argument — trivially
 * testable without a ViewModel, and free of the ordering hazards that came from
 * five independent `combine`/`first` gates each racing its own flag.
 *
 * The `*Watermark` / `*Seen` fields are the persisted "already shown" markers.
 * `0` (levels) / `false` (flags) `Watermark` sentinels mean *unset* — a fresh
 * install / account switch that hasn't been seeded yet. The arbiter treats an
 * unset watermark as "seed, don't celebrate" so a fresh account never blasts a
 * milestone it already had; [needsSeeding] tells the caller which watermarks to
 * advance silently.
 */
data class HomeNotificationSnapshot(
    /** Derived current level (`levelProgressFor(totalXp).level`). Null until progression hydrates. */
    val currentLevel: Int?,
    /** Highest level already celebrated. `0` = unset sentinel. */
    val lastCelebratedLevel: Int,
    /** Aggregated prizes for the levels crossed since [lastCelebratedLevel]. */
    val crossedLevelRewards: List<LevelReward>,

    /** Live "a wallet was created this session" signal (server-sourced). */
    val walletJustCreated: Boolean,
    /** Monotonic: the starter grant was already revealed in onboarding. */
    val didSeeInitialGrantInOnboarding: Boolean,
    /** Welcome-dialog identity, resolved once the profile + wallet hydrate. Null until then. */
    val welcomeIdentity: WelcomeIdentity?,

    /** Play-style hands sampled so far. Null until the style hydrates. */
    val playStyleSampleSize: Long?,
    /** Threshold at which the play-style shape unlocks. */
    val playStyleUnlockThreshold: Long,
    /** Whether the play-style-unlock celebration has already been shown. */
    val playStyleUnlockSeen: Boolean,

    /** Current chip balance. Null until the wallet hydrates. */
    val chipBalance: Long?,
    /** Balance the user last actually saw on Home. Null until the first baseline. */
    val lastShownChipBalance: Long?,
) {

    /** The resolved welcome-dialog identity, present only when all its preconditions align. */
    data class WelcomeIdentity(
        val displayName: String,
        val avatarEmoji: String,
        val avatarBackgroundColorHex: String?,
    )
}

/**
 * Which persisted watermarks are still at their unset sentinel and must be
 * seeded to the current state **without** presenting anything. Kept separate
 * from [GetHomeScreenNotification] so the caller can advance seed watermarks
 * exactly once on hydrate without a celebration ever depending on that write.
 */
data class HomeNotificationSeeds(
    /** Non-null when the level-celebration watermark should be seeded to this level. */
    val seedCelebratedLevel: Int?,
)

/**
 * Persisted watermarks that need a silent seed for this snapshot — the level
 * celebration watermark when it's unset and progression has hydrated.
 *
 * Seeding is deliberately its own concern: a fresh account (or account switch)
 * should adopt its current level as the baseline with no celebration, and that
 * write must not be entangled with "should we show something", or a silent seed
 * could eat a real crossing (the exact PROG-5 failure).
 */
fun HomeNotificationSnapshot.seedsNeeded(): HomeNotificationSeeds {
    val level = currentLevel
    val seedLevel = if (level != null && lastCelebratedLevel == 0) level else null
    return HomeNotificationSeeds(seedCelebratedLevel = seedLevel)
}

/**
 * The single highest-priority **blocking** notification pending for this
 * snapshot, or null if nothing blocking is pending. Pure: same snapshot in →
 * same result out.
 *
 * Priority: [HomeNotification.Welcome] (first-run, once) →
 * [HomeNotification.LevelUp] → [HomeNotification.PlayStyleUnlocked]. An unset
 * level watermark yields no level-up (it seeds instead — see [seedsNeeded]).
 */
fun GetHomeScreenNotification(snapshot: HomeNotificationSnapshot): HomeNotification.Blocking? =
    snapshot.welcome() ?: snapshot.levelUp() ?: snapshot.playStyleUnlocked()

/**
 * The ambient chip-odometer reveal for this snapshot, or null when the balance
 * matches what the user last saw (or hasn't hydrated). Resolved separately from
 * the blocking pick because it coexists with a celebration.
 */
fun HomeNotificationSnapshot.chipDelta(): HomeNotification.ChipDelta? {
    val to = chipBalance ?: return null
    val from = lastShownChipBalance ?: return null
    if (from == to) return null
    return HomeNotification.ChipDelta(from = from, to = to)
}

private fun HomeNotificationSnapshot.welcome(): HomeNotification.Welcome? {
    if (!walletJustCreated || didSeeInitialGrantInOnboarding) return null
    val identity = welcomeIdentity ?: return null
    val chips = chipBalance ?: return null
    return HomeNotification.Welcome(
        displayName = identity.displayName,
        avatarEmoji = identity.avatarEmoji,
        avatarBackgroundColorHex = identity.avatarBackgroundColorHex,
        chips = chips,
    )
}

private fun HomeNotificationSnapshot.levelUp(): HomeNotification.LevelUp? {
    val level = currentLevel ?: return null
    // Unset watermark seeds (see seedsNeeded) rather than celebrating.
    if (lastCelebratedLevel == 0) return null
    if (level <= lastCelebratedLevel) return null
    return HomeNotification.LevelUp(level = level, rewards = crossedLevelRewards)
}

private fun HomeNotificationSnapshot.playStyleUnlocked(): HomeNotification.PlayStyleUnlocked? {
    if (playStyleUnlockSeen) return null
    val sample = playStyleSampleSize ?: return null
    if (sample < playStyleUnlockThreshold) return null
    return HomeNotification.PlayStyleUnlocked
}
