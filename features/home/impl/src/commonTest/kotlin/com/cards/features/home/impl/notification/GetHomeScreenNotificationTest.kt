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
            walletJustCreated = true,
            didSeeInitialGrantInOnboarding = false,
            welcomeIdentity = identity(),
            chipBalance = 10_000,
        )

        val result = GetHomeScreenNotification(snapshot)

        assertTrue(result is HomeNotification.Welcome)
    }

    @Test
    fun `welcome requires a hydrated balance and a resolved identity`() {
        val noBalance = base().copy(
            walletJustCreated = true,
            welcomeIdentity = identity(),
            chipBalance = null,
        )
        val noIdentity = base().copy(
            walletJustCreated = true,
            welcomeIdentity = null,
            chipBalance = 10_000,
        )

        assertNull(GetHomeScreenNotification(noBalance))
        assertNull(GetHomeScreenNotification(noIdentity))
    }

    @Test
    fun `welcome never fires once grant was seen in onboarding`() {
        val snapshot = base().copy(
            walletJustCreated = true,
            didSeeInitialGrantInOnboarding = true,
            welcomeIdentity = identity(),
            chipBalance = 10_000,
        )

        assertNull(GetHomeScreenNotification(snapshot))
    }

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

    private fun base() = HomeNotificationSnapshot(
        currentLevel = null,
        lastCelebratedLevel = 1,
        crossedLevelRewards = emptyList(),
        walletJustCreated = false,
        didSeeInitialGrantInOnboarding = false,
        welcomeIdentity = null,
        playStyleSampleSize = null,
        playStyleUnlockThreshold = 20,
        playStyleUnlockSeen = false,
        chipBalance = null,
        lastShownChipBalance = null,
    )

    private fun identity() = HomeNotificationSnapshot.WelcomeIdentity(
        displayName = "Ada",
        avatarEmoji = "🂡",
        avatarBackgroundColorHex = "#112233",
    )
}
