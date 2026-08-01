package com.dangerfield.cards.libraries.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppDataTest {

    private val fullyPopulated = AppData(
        hasUserOnboarded = true,
        feedbacksGiven = 3,
        bugsReported = 2,
        gameSpeed = GameSpeed.Fast,
        turnFeedback = TurnFeedback.Mute,
        showAchievementPopups = false,
        achievementPopupHintShows = 2,
        reviewInstallAt = 111,
        lastReviewPromptAt = 222,
        swipeFoldGestureAck = true,
        winOddsFlipHintSeen = true,
        didSeeInitialGrantInOnboarding = true,
        welcomeSeen = true,
        tutorialBannerDismissed = true,
        accountSetupExplainerSeen = true,
        lastSessionEndedAt = 333,
        mutedEmojiPlayerKeys = setOf("Rex"),
        shopSeenProductIds = setOf("tool_win_odds", "boost_xp"),
        installId = "install-abc",
        pendingProfileHighlight = "tool_win_odds",
        lastCelebratedLevel = 5,
        xpBoostExpiresAtEpochMs = 999,
        lastShownChipBalance = 4_200,
        xpBoostOwnedCount = 2,
        highestLevelRewarded = 4,
        playStyleUnlockSeen = true,
        outOfChipsSeen = true,
        acceptedLegalVersion = 3,
        legalConsentAcceptedAt = 444,
    )

    @Test
    fun resetAccountScoped_clearsShopSeenProductIds() {
        assertEquals(emptySet(), fullyPopulated.resetAccountScoped().shopSeenProductIds)
    }

    @Test
    fun resetAccountScoped_clearsLastShownChipBalance() {
        assertNull(fullyPopulated.resetAccountScoped().lastShownChipBalance)
    }

    @Test
    fun resetAccountScoped_clearsTheAccountScopedProgressionFields() {
        val reset = fullyPopulated.resetAccountScoped()

        assertFalse(reset.didSeeInitialGrantInOnboarding)
        assertFalse(reset.welcomeSeen)
        assertEquals(0, reset.lastCelebratedLevel)
        assertNull(reset.xpBoostExpiresAtEpochMs)
        assertEquals(0, reset.xpBoostOwnedCount)
        assertEquals(0, reset.highestLevelRewarded)
        assertFalse(reset.tutorialBannerDismissed)
        assertFalse(reset.playStyleUnlockSeen)
        assertFalse(reset.outOfChipsSeen)
    }

    @Test
    fun resetAccountScoped_preservesDeviceScopedState() {
        val reset = fullyPopulated.resetAccountScoped()

        assertTrue(reset.hasUserOnboarded)
        assertEquals("install-abc", reset.installId)
        assertEquals(GameSpeed.Fast, reset.gameSpeed)
        assertEquals(TurnFeedback.Mute, reset.turnFeedback)
        assertFalse(reset.showAchievementPopups)
        assertEquals(2, reset.achievementPopupHintShows)
        assertEquals(111, reset.reviewInstallAt)
        assertEquals(222, reset.lastReviewPromptAt)
        assertTrue(reset.swipeFoldGestureAck)
        assertTrue(reset.accountSetupExplainerSeen)
        assertEquals(333, reset.lastSessionEndedAt)
    }
}
