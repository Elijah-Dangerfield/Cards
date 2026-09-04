package com.dangerfield.cards.features.home.impl.notification

import com.dangerfield.cards.libraries.cards.AppVersion
import com.dangerfield.cards.libraries.cards.LevelReward
import com.dangerfield.cards.libraries.cards.isWorthPromptingFrom

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

    /**
     * Authoritative "this account was just created this session" signal
     * (server `/v1/me` `isNewAccount`, latched in the profile repo). Replaced
     * the `walletJustCreated` proxy that tripped on identity churn — a
     * pre-existing account signing in could look "new" and get the welcome.
     */
    val accountJustCreated: Boolean,
    /** Monotonic: the starter grant was already revealed in onboarding — gates the
     *  dialog's *reveal section*, not whether the dialog shows. */
    val didSeeInitialGrantInOnboarding: Boolean,
    /** Whether the one-time welcome dialog has already been presented to this
     *  account. The dialog's true once-per-user gate (see `AppData.welcomeSeen`). */
    val welcomeSeen: Boolean,
    /** Whether the founding-member window is open right now. Resolved by the
     *  ViewModel against the device clock so the arbiter stays pure + clock-free. */
    val inFoundingWindow: Boolean,
    /** Welcome-dialog identity, resolved once the profile + wallet hydrate. Null until then. */
    val welcomeIdentity: WelcomeIdentity?,
    /**
     * The explicit starter-grant amount (server-driven `onboarding.starterGrant`
     * config) the welcome dialog reveals. Null when config hasn't resolved
     * (offline / pre-config) — the welcome then falls back to the fresh account's
     * balance, which equals the grant for a brand-new account. This is what fixed
     * the dialog showing the user's whole balance as the "gift".
     */
    val starterGrant: Long?,

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

    /** Whether the out-of-chips sheet already showed for this below-buy-in episode. */
    val outOfChipsSeen: Boolean,
    /** The cheapest standard buy-in (`StakeTier.Casual.buyIn`) — the out-of-chips line. */
    val casualBuyIn: Long,

    /**
     * [AchievementId] names earned in a real-chip MP game with no at-table reveal,
     * queued at hand-end (`AppData.pendingHomeAchievementIds`) for a Home
     * celebration (PROG-13). Empty when nothing is pending. Unlike the watermark
     * fields this is a drain queue, not a monotonic marker: presenting it clears
     * the queue rather than advancing a high-water mark.
     */
    val pendingAchievementIds: List<String>,

    /**
     * The running app's own version (`BuildInfo.versionName`). Never null — the
     * build always knows what it is.
     */
    val installedVersion: String = "",
    /**
     * The newest version the store will give this user, or null when the check
     * hasn't answered (offline, API failure, sideload). Null means "don't
     * prompt", never "up to date".
     */
    val latestStoreVersion: String? = null,
    /**
     * The version we last surfaced an update prompt for, blank when we never
     * have. Keyed by version rather than a boolean so skipping one feature
     * release still earns one ask on the next.
     */
    val lastPromptedUpdateVersion: String = "",
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
 * Priority: [HomeNotification.Welcome] (once per account) →
 * [HomeNotification.AchievementsEarned] → [HomeNotification.LevelUp] →
 * [HomeNotification.PlayStyleUnlocked] → [HomeNotification.OutOfChips] →
 * [HomeNotification.UpdateAvailable]. An unset
 * level watermark yields no level-up (it seeds instead — see [seedsNeeded]).
 * Out-of-chips is deliberately last: a level-up may grant chips that resolve the
 * shortfall before we point at the shop.
 *
 * Achievements sit above the level-up: they're the concrete thing the player
 * just earned with no other surface, whereas the level-up is derived state that
 * survives on its watermark and simply fires on the next settle after the
 * achievement queue drains.
 */
fun GetHomeScreenNotification(snapshot: HomeNotificationSnapshot): HomeNotification.Blocking? =
    snapshot.welcome()
        ?: snapshot.achievementsEarned()
        ?: snapshot.levelUp()
        ?: snapshot.playStyleUnlocked()
        ?: snapshot.outOfChips()
        ?: snapshot.updateAvailable()

/**
 * True when the persisted [HomeNotificationSnapshot.outOfChipsSeen] episode
 * marker should flip back to false — the balance has recovered to at least
 * the Casual buy-in, closing the episode. Pure, like the arbiter; the caller
 * owns the write.
 */
fun HomeNotificationSnapshot.outOfChipsResetNeeded(): Boolean {
    val balance = chipBalance ?: return false
    return outOfChipsSeen && balance >= casualBuyIn
}

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
    // Once per account, full stop — this is what lets the founding copy reach an
    // existing early player exactly once rather than never (they'd fail the
    // "just created" test the old gate leaned on).
    if (welcomeSeen) return null
    val identity = welcomeIdentity ?: return null

    // The dialog earns a showing only when it has something to say: a brand-new
    // account still owed its starter-grant reveal (the backup reveal, when
    // onboarding's reveal degraded), or an open founding-member window.
    val owesBackupReveal = accountJustCreated && !didSeeInitialGrantInOnboarding
    if (!owesBackupReveal && !inFoundingWindow) return null

    val grantReveal = if (owesBackupReveal) {
        when {
            // Prefer the explicit server grant.
            starterGrant != null -> HomeNotification.Welcome.GrantReveal.Exact(starterGrant)
            // Still hydrating — wait for the real number rather than flash "landing
            // soon" and never correct it (the dialog shows exactly once).
            chipBalance == null -> return null
            // A fresh account's balance equals its grant before they've played.
            chipBalance > 0 -> HomeNotification.Welcome.GrantReveal.Exact(chipBalance)
            // Hydrated to zero with no grant config — offline / the grant hasn't
            // posted. Promise the chips instead of revealing a wrong or zero number.
            else -> HomeNotification.Welcome.GrantReveal.Pending
        }
    } else {
        null
    }

    return HomeNotification.Welcome(
        displayName = identity.displayName,
        avatarEmoji = identity.avatarEmoji,
        avatarBackgroundColorHex = identity.avatarBackgroundColorHex,
        grantReveal = grantReveal,
        isFounding = inFoundingWindow,
    )
}

private fun HomeNotificationSnapshot.achievementsEarned(): HomeNotification.AchievementsEarned? {
    if (pendingAchievementIds.isEmpty()) return null
    return HomeNotification.AchievementsEarned(achievementIds = pendingAchievementIds)
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

private fun HomeNotificationSnapshot.outOfChips(): HomeNotification.OutOfChips? {
    if (outOfChipsSeen) return null
    val balance = chipBalance ?: return null
    if (balance >= casualBuyIn) return null
    // A brand-new account is mid-reveal — the starter grant covers the buy-in
    // anyway, but don't stack this behind the welcome dialog on the same visit.
    if (accountJustCreated) return null
    return HomeNotification.OutOfChips(balance = balance, casualBuyIn = casualBuyIn)
}

/**
 * The update prompt, when the store is far enough ahead to be worth a Home slot.
 *
 * Everything interesting is in [isWorthPromptingFrom]; this only translates the
 * persisted strings into versions and refuses to prompt on anything it can't
 * parse. An unparseable store version is treated as no answer, not as a
 * mismatch — a prompt driven by a misread version points people at an update
 * that isn't there.
 */
private fun HomeNotificationSnapshot.updateAvailable(): HomeNotification.UpdateAvailable? {
    val latest = AppVersion.parseOrNull(latestStoreVersion) ?: return null
    val installed = AppVersion.parseOrNull(installedVersion) ?: return null
    val lastPrompted = AppVersion.parseOrNull(lastPromptedUpdateVersion)
    if (!latest.isWorthPromptingFrom(installed, lastPrompted)) return null
    return HomeNotification.UpdateAvailable(latestVersion = latest.toString())
}
