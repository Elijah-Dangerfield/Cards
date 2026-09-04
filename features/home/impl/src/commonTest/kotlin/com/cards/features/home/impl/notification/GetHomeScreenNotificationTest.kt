package com.dangerfield.cards.features.home.impl.notification

import com.dangerfield.cards.libraries.cards.LevelReward
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GetHomeScreenNotificationTest {

    @Test
    fun `unset level watermark seeds and does not celebrate`() {
        val snapshot = base().copy(currentLevel = 7, lastCelebratedLevel = 0)

        assertNull(GetHomeScreenNotification(snapshot))
        assertEquals(7, snapshot.seedsNeeded().seedCelebratedLevel)
    }

    @Test
    fun `a pending level crossing surfaces once the watermark is set`() {
        val rewards = listOf(LevelReward.Chips(500))
        val snapshot = base().copy(
            currentLevel = 4,
            lastCelebratedLevel = 3,
            crossedLevelRewards = rewards,
        )

        val result = GetHomeScreenNotification(snapshot)

        assertEquals(HomeNotification.LevelUp(level = 4, rewards = rewards), result)
        assertNull(snapshot.seedsNeeded().seedCelebratedLevel)
    }

    @Test
    fun `no level-up when current level is at the watermark`() {
        val snapshot = base().copy(currentLevel = 5, lastCelebratedLevel = 5)

        assertNull(GetHomeScreenNotification(snapshot))
    }

    @Test
    fun `multi-level jump celebrates the net level once`() {
        val snapshot = base().copy(
            currentLevel = 9,
            lastCelebratedLevel = 6,
            crossedLevelRewards = listOf(LevelReward.Chips(300), LevelReward.XpBoost()),
        )

        val result = GetHomeScreenNotification(snapshot)

        assertTrue(result is HomeNotification.LevelUp)
        assertEquals(9, (result as HomeNotification.LevelUp).level)
    }

    @Test
    fun `welcome outranks a pending level-up`() {
        val snapshot = base().copy(
            currentLevel = 4,
            lastCelebratedLevel = 3,
            accountJustCreated = true,
            didSeeInitialGrantInOnboarding = false,
            welcomeIdentity = identity(),
            chipBalance = 10_000,
        )

        val result = GetHomeScreenNotification(snapshot)

        assertTrue(result is HomeNotification.Welcome)
    }

    @Test
    fun `welcome requires a resolved identity`() {
        val noIdentity = base().copy(
            accountJustCreated = true,
            welcomeIdentity = null,
            chipBalance = 10_000,
        )

        assertNull(GetHomeScreenNotification(noIdentity))
    }

    @Test
    fun `welcome waits for a number rather than presenting a premature pending reveal`() {
        // No grant config and the wallet hasn't hydrated (null) — hold, so an
        // online account gets its real number instead of a flash of "landing soon"
        // that the once-only dialog can never correct.
        val stillHydrating = base().copy(
            accountJustCreated = true,
            welcomeIdentity = identity(),
            starterGrant = null,
            chipBalance = null,
        )

        assertNull(GetHomeScreenNotification(stillHydrating))
    }

    @Test
    fun `welcome falls back to a pending reveal when hydrated to zero with no grant config`() {
        // Wallet resolved to zero and no grant config (offline / grant not posted)
        // — fire, promising the chips rather than revealing a wrong number.
        val snapshot = base().copy(
            accountJustCreated = true,
            welcomeIdentity = identity(),
            starterGrant = null,
            chipBalance = 0,
        )

        val result = GetHomeScreenNotification(snapshot)

        assertTrue(result is HomeNotification.Welcome)
        assertEquals(
            HomeNotification.Welcome.GrantReveal.Pending,
            (result as HomeNotification.Welcome).grantReveal,
        )
    }

    @Test
    fun `welcome does not re-reveal a grant already seen in onboarding (outside the founding window)`() {
        val snapshot = base().copy(
            accountJustCreated = true,
            didSeeInitialGrantInOnboarding = true,
            welcomeIdentity = identity(),
            chipBalance = 10_000,
        )

        assertNull(GetHomeScreenNotification(snapshot))
    }

    @Test
    fun `welcome reveals the explicit starter grant, not the wallet balance`() {
        // Bug (AUTH-23): the dialog showed the user's whole balance as the "gift".
        val snapshot = base().copy(
            accountJustCreated = true,
            welcomeIdentity = identity(),
            starterGrant = 10_000,
            chipBalance = 132_250, // a larger balance must NOT be what's revealed
        )

        val result = GetHomeScreenNotification(snapshot)

        assertTrue(result is HomeNotification.Welcome)
        assertEquals(
            HomeNotification.Welcome.GrantReveal.Exact(10_000),
            (result as HomeNotification.Welcome).grantReveal,
        )
    }

    @Test
    fun `welcome never fires for a pre-existing account outside the founding window`() {
        // Bug (AUTH-23): an established account signing in must not see the plain
        // starter-grant welcome. accountJustCreated gates the reveal; the founding
        // window is the only other way in.
        val snapshot = base().copy(
            accountJustCreated = false,
            welcomeIdentity = identity(),
            starterGrant = 10_000,
            chipBalance = 132_250,
        )

        assertNull(GetHomeScreenNotification(snapshot))
    }

    @Test
    fun `founding window shows the welcome to an existing player with no reveal`() {
        val snapshot = base().copy(
            accountJustCreated = false,
            inFoundingWindow = true,
            welcomeIdentity = identity(),
            chipBalance = 132_250,
        )

        val result = GetHomeScreenNotification(snapshot)

        assertTrue(result is HomeNotification.Welcome)
        result as HomeNotification.Welcome
        assertTrue(result.isFounding)
        assertNull(result.grantReveal)
    }

    @Test
    fun `founding new account gets both the reveal and the founding copy`() {
        val snapshot = base().copy(
            accountJustCreated = true,
            inFoundingWindow = true,
            welcomeIdentity = identity(),
            starterGrant = 10_000,
        )

        val result = GetHomeScreenNotification(snapshot)

        assertTrue(result is HomeNotification.Welcome)
        result as HomeNotification.Welcome
        assertTrue(result.isFounding)
        assertEquals(HomeNotification.Welcome.GrantReveal.Exact(10_000), result.grantReveal)
    }

    @Test
    fun `welcomeSeen suppresses the dialog even inside the founding window`() {
        val snapshot = base().copy(
            welcomeSeen = true,
            accountJustCreated = true,
            inFoundingWindow = true,
            welcomeIdentity = identity(),
            starterGrant = 10_000,
        )

        assertNull(GetHomeScreenNotification(snapshot))
    }

    @Test
    fun `pending MP achievements surface as a blocking celebration`() {
        val snapshot = base().copy(pendingAchievementIds = listOf("HANDS_10", "POT_5000"))

        assertEquals(
            HomeNotification.AchievementsEarned(listOf("HANDS_10", "POT_5000")),
            GetHomeScreenNotification(snapshot),
        )
    }

    @Test
    fun `no achievement notification when the queue is empty`() {
        assertNull(base().copy(pendingAchievementIds = emptyList()).achievementsEarnedOrNull())
    }

    @Test
    fun `achievements outrank a pending level-up`() {
        val snapshot = base().copy(
            currentLevel = 4,
            lastCelebratedLevel = 3,
            pendingAchievementIds = listOf("HANDS_10"),
        )

        assertTrue(GetHomeScreenNotification(snapshot) is HomeNotification.AchievementsEarned)
    }

    @Test
    fun `welcome outranks pending achievements`() {
        val snapshot = base().copy(
            accountJustCreated = true,
            welcomeIdentity = identity(),
            chipBalance = 10_000,
            pendingAchievementIds = listOf("HANDS_10"),
        )

        assertTrue(GetHomeScreenNotification(snapshot) is HomeNotification.Welcome)
    }

    private fun HomeNotificationSnapshot.achievementsEarnedOrNull(): HomeNotification.AchievementsEarned? =
        GetHomeScreenNotification(this) as? HomeNotification.AchievementsEarned

    @Test
    fun `play-style unlock fires once the sample crosses the threshold`() {
        val snapshot = base().copy(
            playStyleSampleSize = 20,
            playStyleUnlockThreshold = 20,
            playStyleUnlockSeen = false,
        )

        assertEquals(HomeNotification.PlayStyleUnlocked, GetHomeScreenNotification(snapshot))
    }

    @Test
    fun `play-style unlock does not fire below the threshold or once seen`() {
        val below = base().copy(playStyleSampleSize = 19, playStyleUnlockThreshold = 20)
        val seen = base().copy(
            playStyleSampleSize = 40,
            playStyleUnlockThreshold = 20,
            playStyleUnlockSeen = true,
        )

        assertNull(GetHomeScreenNotification(below))
        assertNull(GetHomeScreenNotification(seen))
    }

    @Test
    fun `level-up outranks play-style unlock`() {
        val snapshot = base().copy(
            currentLevel = 4,
            lastCelebratedLevel = 3,
            playStyleSampleSize = 40,
            playStyleUnlockThreshold = 20,
        )

        assertTrue(GetHomeScreenNotification(snapshot) is HomeNotification.LevelUp)
    }

    @Test
    fun `chip delta is ambient and resolves independently of the blocking pick`() {
        val snapshot = base().copy(
            currentLevel = 4,
            lastCelebratedLevel = 3,
            chipBalance = 1_200,
            lastShownChipBalance = 1_000,
        )

        assertTrue(GetHomeScreenNotification(snapshot) is HomeNotification.LevelUp)
        assertEquals(HomeNotification.ChipDelta(from = 1_000, to = 1_200), snapshot.chipDelta())
    }

    @Test
    fun `chip delta is null when balance matches last seen or has not hydrated`() {
        val same = base().copy(chipBalance = 1_000, lastShownChipBalance = 1_000)
        val noBaseline = base().copy(chipBalance = 1_000, lastShownChipBalance = null)

        assertNull(same.chipDelta())
        assertNull(noBaseline.chipDelta())
    }

    @Test
    fun `out of chips fires below the buy-in when unseen`() {
        val snapshot = base().copy(chipBalance = 400, outOfChipsSeen = false)

        assertEquals(
            HomeNotification.OutOfChips(balance = 400, casualBuyIn = 1_000),
            GetHomeScreenNotification(snapshot),
        )
    }

    @Test
    fun `out of chips stays quiet once seen this episode`() {
        val snapshot = base().copy(chipBalance = 400, outOfChipsSeen = true)

        assertNull(GetHomeScreenNotification(snapshot))
    }

    @Test
    fun `out of chips requires a hydrated balance`() {
        val snapshot = base().copy(chipBalance = null)

        assertNull(GetHomeScreenNotification(snapshot))
    }

    @Test
    fun `a balance at the buy-in is not out of chips`() {
        val snapshot = base().copy(chipBalance = 1_000)

        assertNull(GetHomeScreenNotification(snapshot))
    }

    @Test
    fun `out of chips defers to a brand-new wallet's welcome moment`() {
        // Wallet just created but the welcome identity hasn't resolved yet — the
        // arbiter must not slip an out-of-chips sheet into that gap.
        val snapshot = base().copy(
            accountJustCreated = true,
            welcomeIdentity = null,
            chipBalance = 400,
        )

        assertNull(GetHomeScreenNotification(snapshot))
    }

    @Test
    fun `a pending level-up outranks out of chips`() {
        val snapshot = base().copy(
            currentLevel = 4,
            lastCelebratedLevel = 3,
            chipBalance = 400,
        )

        assertTrue(GetHomeScreenNotification(snapshot) is HomeNotification.LevelUp)
    }

    @Test
    fun `episode reset is needed only after the balance recovers`() {
        val recovered = base().copy(chipBalance = 2_000, outOfChipsSeen = true)
        val stillBroke = base().copy(chipBalance = 400, outOfChipsSeen = true)
        val neverSeen = base().copy(chipBalance = 2_000, outOfChipsSeen = false)
        val notHydrated = base().copy(chipBalance = null, outOfChipsSeen = true)

        assertTrue(recovered.outOfChipsResetNeeded())
        assertTrue(!stillBroke.outOfChipsResetNeeded())
        assertTrue(!neverSeen.outOfChipsResetNeeded())
        assertTrue(!notHydrated.outOfChipsResetNeeded())
    }

    private fun base() = HomeNotificationSnapshot(
        currentLevel = null,
        lastCelebratedLevel = 1,
        crossedLevelRewards = emptyList(),
        accountJustCreated = false,
        didSeeInitialGrantInOnboarding = false,
        welcomeSeen = false,
        inFoundingWindow = false,
        welcomeIdentity = null,
        starterGrant = null,
        playStyleSampleSize = null,
        playStyleUnlockThreshold = 20,
        playStyleUnlockSeen = false,
        chipBalance = null,
        lastShownChipBalance = null,
        outOfChipsSeen = false,
        casualBuyIn = 1_000,
        pendingAchievementIds = emptyList(),
    )

    // ── update prompt (lowest priority of the blocking band) ────────────────

    @Test
    fun updatePrompt_surfacesOnAFeatureRelease() {
        val result = GetHomeScreenNotification(
            base().copy(installedVersion = "0.2.0", latestStoreVersion = "0.3.0"),
        )
        assertEquals(HomeNotification.UpdateAvailable("0.3.0"), result)
    }

    @Test
    fun updatePrompt_staysSilentOnAPatchRelease() {
        val result = GetHomeScreenNotification(
            base().copy(installedVersion = "0.2.0", latestStoreVersion = "0.2.3"),
        )
        assertNull(result)
    }

    @Test
    fun updatePrompt_staysSilentOnceAskedAboutThatVersion() {
        val result = GetHomeScreenNotification(
            base().copy(
                installedVersion = "0.2.0",
                latestStoreVersion = "0.3.0",
                lastPromptedUpdateVersion = "0.3.0",
            ),
        )
        assertNull(result)
    }

    @Test
    fun updatePrompt_staysSilentWhenTheStoreCheckHasNoAnswer() {
        // Offline, API failure, or a sideload: null must mean "don't prompt",
        // never "up to date".
        val result = GetHomeScreenNotification(
            base().copy(installedVersion = "0.2.0", latestStoreVersion = null),
        )
        assertNull(result)
    }

    @Test
    fun updatePrompt_yieldsToEveryOtherBlockingNotification() {
        // It is the lowest priority in the band: an out-of-chips shortfall, the
        // least urgent of the others, still wins the slot.
        val result = GetHomeScreenNotification(
            base().copy(
                installedVersion = "0.2.0",
                latestStoreVersion = "0.3.0",
                chipBalance = 10,
                casualBuyIn = 1_000,
                outOfChipsSeen = false,
            ),
        )
        assertTrue(result is HomeNotification.OutOfChips, "got $result")
    }

    private fun identity() = HomeNotificationSnapshot.WelcomeIdentity(
        displayName = "Ada",
        avatarEmoji = "🂡",
        avatarBackgroundColorHex = "#112233",
    )
}
