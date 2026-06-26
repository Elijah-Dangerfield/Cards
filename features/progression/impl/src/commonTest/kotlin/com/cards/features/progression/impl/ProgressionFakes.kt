package com.dangerfield.cards.features.progression.impl

import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.LifetimeStatsRepository
import com.dangerfield.cards.libraries.cards.PlayStyleAxes
import com.dangerfield.cards.libraries.cards.PlayStyleHandSummary
import com.dangerfield.cards.libraries.cards.PlayStyleRepository
import com.dangerfield.cards.libraries.cards.PlayerStatHandSummary
import com.dangerfield.cards.libraries.cards.PlayerStats
import com.dangerfield.cards.libraries.cards.PlayerStatsRepository
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.XpBoostRepository
import com.dangerfield.cards.libraries.cards.XpBoostStatus
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpEventRepository
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.DeleteAccountOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkEmailIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.RefreshOutcome
import com.dangerfield.cards.libraries.identity.auth.ResendOutcome
import com.dangerfield.cards.libraries.identity.auth.SendResetOutcome
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.auth.SignUpOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    override suspend fun sync(): Result<Unit> = Result.success(Unit)
    override suspend fun deleteAll() { /* not used here */ }
    override suspend fun debugSetTotalXp(totalXp: Long) {
        progression.value = progression.value.copy(totalXp = totalXp)
    }
}

internal class FakePlayStyleRepository(
    initial: PlayStyleAxes? = null,
) : PlayStyleRepository {
    val style = MutableStateFlow(initial)
    override fun observeOwnStyle(): Flow<PlayStyleAxes?> = style
    override suspend fun getOwnStyle(): PlayStyleAxes? = style.value
    override suspend fun recordHand(summary: PlayStyleHandSummary) =
        error("recordHand not used by the progression VMs")
    override suspend fun sync(): Result<Unit> = Result.success(Unit)
    override suspend fun getStyleFor(userId: String): Result<PlayStyleAxes?> =
        error("getStyleFor not used by the progression VMs")
    override suspend fun deleteAll() { /* not used here */ }
}

internal class FakeXpEventRepository(
    initial: List<XpEvent> = emptyList(),
) : XpEventRepository {
    val events = MutableStateFlow(initial)
    override fun observeRecent(limit: Int): Flow<List<XpEvent>> = events
    override fun observeSince(sinceEpochMs: Long): Flow<List<XpEvent>> = events
}

internal class FakePlayerStatsRepository(
    initial: PlayerStats? = null,
) : PlayerStatsRepository {
    val stats = MutableStateFlow(initial)
    val effectiveCounters = MutableStateFlow<Map<String, Long>>(emptyMap())
    override fun observeStats(): Flow<PlayerStats?> = stats
    override suspend fun getStats(): PlayerStats? = stats.value
    override fun observeEffectiveCounters(): Flow<Map<String, Long>> = effectiveCounters
    override suspend fun effectiveCounters(): Map<String, Long> = effectiveCounters.value
    override suspend fun recordHand(summary: PlayerStatHandSummary) =
        error("recordHand not used by the progression VMs")
    override suspend fun sync(): Result<Unit> = Result.success(Unit)
    override suspend fun deleteAll() { /* not used here */ }
}

internal class FakeLifetimeStatsRepository(
    private val distinctOpponents: Result<Long> = Result.success(0L),
) : LifetimeStatsRepository {
    override suspend fun fetchDistinctOpponents(): Result<Long> = distinctOpponents
}

internal class FakeXpBoostRepository(
    initial: XpBoostStatus = XpBoostStatus.None,
) : XpBoostRepository {
    val status = MutableStateFlow(initial)
    override fun observe(): Flow<XpBoostStatus> = status
    override suspend fun status(): XpBoostStatus = status.value
    override suspend fun grant(count: Int) { /* not exercised by the read-only VMs */ }
    override suspend fun activate(durationMs: Long): Boolean = false
    override suspend fun multiplier(): Int = 1
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
    override suspend fun recordTutorialComplete(): EarnedAchievement? =
        error("recordTutorialComplete not used by the progression VMs")
    override suspend fun sync(): Result<Unit> = Result.success(Unit)
    override suspend fun deleteAll() { /* not used here */ }
}

/**
 * Minimal [AuthRepository] fake — exposes a settable state flow and
 * stubs every mutation as `error()` since the progression VMs only read.
 */
internal class FakeAuthRepository(
    initial: AuthState? = null,
) : AuthRepository {
    val state: MutableSharedFlow<AuthState> = MutableSharedFlow<AuthState>(replay = 1).also {
        if (initial != null) it.tryEmit(initial)
    }

    override suspend fun current(): AuthState = state.replayCache.first()
    override fun observe(): Flow<AuthState> = state

    fun emit(next: AuthState) {
        state.tryEmit(next)
    }

    override suspend fun retry(): AuthState = error("not used")
    override suspend fun signInWithEmail(email: String, password: String): SignInOutcome =
        error("not used")
    override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome =
        error("not used")
    override suspend fun refreshSession(): RefreshOutcome = error("not used")
    override suspend fun resendVerificationEmail(email: String): ResendOutcome =
        error("not used")
    override suspend fun sendPasswordResetEmail(email: String): SendResetOutcome =
        error("not used")
    override suspend fun signOut() { error("not used") }
    override suspend fun deleteAccount(): DeleteAccountOutcome = error("not used")
    override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome =
        error("not used")
    override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome =
        error("not used")
    override suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome =
        error("not used")
}

internal fun anonymousAuthState(userId: String = "user-1"): AuthState.Authenticated =
    AuthState.Authenticated(userId = userId, isAnonymous = true, email = null)

internal fun claimedAuthState(userId: String = "user-1"): AuthState.Authenticated =
    AuthState.Authenticated(userId = userId, isAnonymous = false, email = "real@example.com")
