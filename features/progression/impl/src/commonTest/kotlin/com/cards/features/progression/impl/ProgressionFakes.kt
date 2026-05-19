package com.dangerfield.cards.features.progression.impl

import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.User
import com.dangerfield.cards.libraries.cards.UserRepository
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpEventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared in-memory fakes for the progression-impl ViewModels. The VMs are
 * thin "subscribe and map" orchestrators, so the fakes just expose state
 * flows the test can push through.
 *
 * Methods the VMs never call are `error()`-stubbed so a future refactor
 * that starts using them fails loudly instead of silently passing.
 */
internal class FakeProgressionRepository(
    initial: Progression = Progression.Empty,
) : ProgressionRepository {
    val progression = MutableStateFlow(initial)
    override fun observeProgression(): Flow<Progression> = progression
    override suspend fun getProgression(): Progression = progression.value
    override suspend fun awardForHand(summary: HandResultSummary): List<XpEvent> =
        error("awardForHand not used by the progression VMs")
    override suspend fun applyAchievementXp(delta: Int, description: String?): XpEvent =
        error("applyAchievementXp not used by the progression VMs")
    override suspend fun deleteAll() { /* not used here */ }
}

internal class FakeXpEventRepository(
    initial: List<XpEvent> = emptyList(),
) : XpEventRepository {
    val events = MutableStateFlow(initial)
    override fun observeRecent(limit: Int): Flow<List<XpEvent>> = events
    override fun observeSince(sinceEpochMs: Long): Flow<List<XpEvent>> = events
}

internal class FakeAchievementRepository(
    initial: AchievementProgress = AchievementProgress.Empty,
) : AchievementRepository {
    val progress = MutableStateFlow(initial)
    override fun observeProgress(): Flow<AchievementProgress> = progress
    override suspend fun getProgress(): AchievementProgress = progress.value
    override suspend fun recordHand(
        summary: HandResultSummary,
        context: AchievementHandContext,
    ): List<EarnedAchievement> = error("recordHand not used by the progression VMs")
    override suspend fun deleteAll() { /* not used here */ }
}

internal class FakeUserRepository(initial: User? = null) : UserRepository {
    val user = MutableStateFlow(initial)
    override suspend fun ensureUserExists() { /* not used here */ }
    override fun observeUser(): Flow<User?> = user
    override suspend fun getUser(): User? = user.value
    override suspend fun setName(name: String?) {
        user.value = user.value?.copy(name = name)
    }
    override suspend fun onSessionStarted() { /* not used here */ }
    override suspend fun onAppOpened() { /* not used here */ }
    override suspend fun setOnboardingComplete() { /* not used here */ }
    override suspend fun onShakeDetected() { /* not used here */ }
    override suspend fun deleteAll() {
        user.value = null
    }
}

internal fun anonymousUser(): User = User(
    name = "QuietAce72",
    createdAt = 1_700_000_000_000,
    lastSessionAt = 1_700_000_000_000,
    hasCompletedOnboarding = true,
    isAnonymous = true,
    sessionsCount = 1,
    appOpenCount = 1,
)

internal fun claimedUser(): User = anonymousUser().copy(isAnonymous = false)
