package com.dangerfield.cards.features.room.impl.tutorial

import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AllAchievementsById
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the [TutorialViewModel] state machine — advance / goBack /
 * skipBasics / restartBasics / submit, plus the final-step branch into
 * `recordTutorialComplete` (first-time vs replay vs grant failure).
 */
class TutorialViewModelTest : CoroutineTest() {

    private val script = TutorialScript.steps
    private val firstNonBasicsIndex = script.indexOfFirst { !it.isBasics }
    private val firstBasicsIndex = script.indexOfFirst { it.isBasics }

    @Test
    fun initialState_isFirstScriptStep() = runUnitTest {
        val vm = buildVm()

        val state = vm.state.value
        assertSame(script.first(), state.step)
        assertEquals(0, state.stepIndex)
        assertEquals(script.size, state.totalSteps)
        assertEquals(false, state.completed)
        assertNull(state.wasFirstCompletion)
    }

    @Test
    fun advance_movesToNextScriptStep() = runUnitTest {
        val vm = buildVm()

        vm.advance()

        val state = vm.state.value
        assertEquals(1, state.stepIndex)
        assertSame(script[1], state.step)
        assertEquals(false, state.completed)
    }

    @Test
    fun goBack_noopsOnFirstStep() = runUnitTest {
        val vm = buildVm()

        vm.goBack()

        assertEquals(0, vm.state.value.stepIndex)
    }

    @Test
    fun goBack_returnsToPreviousStep() = runUnitTest {
        val vm = buildVm()
        vm.advance()
        vm.advance()

        vm.goBack()

        val state = vm.state.value
        assertEquals(1, state.stepIndex)
        assertSame(script[1], state.step)
    }

    @Test
    fun skipBasics_jumpsToFirstNonBasicsStep() = runUnitTest {
        assertTrue(firstNonBasicsIndex > 0, "test fixture assumes a basics block exists")
        val vm = buildVm()

        vm.skipBasics()

        assertEquals(firstNonBasicsIndex, vm.state.value.stepIndex)
    }

    @Test
    fun skipBasics_isNoopOncePastBasics() = runUnitTest {
        val vm = buildVm()
        vm.skipBasics()
        val beforeIndex = vm.state.value.stepIndex

        vm.skipBasics()

        assertEquals(beforeIndex, vm.state.value.stepIndex)
    }

    @Test
    fun restartBasics_rewindsToFirstBasicsStep() = runUnitTest {
        assertTrue(firstBasicsIndex >= 0)
        val vm = buildVm()
        vm.skipBasics()
        assertNotEquals(firstBasicsIndex, vm.state.value.stepIndex)

        vm.restartBasics()

        assertEquals(firstBasicsIndex, vm.state.value.stepIndex)
    }

    @Test
    fun submit_advancesWhenStepPredicateMatches() = runUnitTest {
        val (matchingIntent, indexBeforeMatch) = firstAdvanceableStep()
        val vm = buildVm()
        repeat(indexBeforeMatch) { vm.advance() }
        assertEquals(indexBeforeMatch, vm.state.value.stepIndex)

        vm.submit(matchingIntent)

        assertEquals(indexBeforeMatch + 1, vm.state.value.stepIndex)
    }

    @Test
    fun submit_isNoopWhenStepHasNoPredicate() = runUnitTest {
        val vm = buildVm()
        val current = vm.state.value
        assertNull(current.step.advanceOn, "test fixture assumes the first step is narration-only")

        vm.submit(PlayerIntent.Fold(seatIndex = 0))

        assertEquals(0, vm.state.value.stepIndex)
    }

    @Test
    fun submit_isNoopWhenIntentDoesNotMatchPredicate() = runUnitTest {
        val (matchingIntent, indexBeforeMatch) = firstAdvanceableStep()
        val nonMatching = nonMatchingIntent(matchingIntent)
        val vm = buildVm()
        repeat(indexBeforeMatch) { vm.advance() }

        vm.submit(nonMatching)

        assertEquals(indexBeforeMatch, vm.state.value.stepIndex)
    }

    @Test
    fun advance_pastFinalStep_marksCompleted_andResolvesFirstCompletion() = runUnitTest {
        val achievements = TutorialAchievementsFake(tutorialEarned = aTutorialEarnedAchievement())
        val vm = buildVm(achievements = achievements)

        runScriptToCompletion(vm)

        val state = vm.state.value
        assertEquals(true, state.completed)
        assertEquals(true, state.wasFirstCompletion)
        assertEquals(1, achievements.recordCalls)
    }

    @Test
    fun advance_pastFinalStep_resolvesReplayWhenRepoReturnsNull() = runUnitTest {
        val achievements = TutorialAchievementsFake(tutorialEarned = null)
        val vm = buildVm(achievements = achievements)

        runScriptToCompletion(vm)

        val state = vm.state.value
        assertEquals(true, state.completed)
        assertEquals(false, state.wasFirstCompletion)
    }

    @Test
    fun advance_pastFinalStep_fallsBackToFirstCompletion_whenRepoThrows() = runUnitTest {
        val achievements = TutorialAchievementsFake(throwOnRecord = true)
        val vm = buildVm(achievements = achievements)

        runScriptToCompletion(vm)

        val state = vm.state.value
        assertEquals(true, state.completed)
        assertEquals(true, state.wasFirstCompletion, "failure path defaults to first-completion celebration")
    }

    @Test
    fun advance_afterCompletion_isNoop() = runUnitTest {
        val achievements = TutorialAchievementsFake(tutorialEarned = aTutorialEarnedAchievement())
        val vm = buildVm(achievements = achievements)
        runScriptToCompletion(vm)

        vm.advance()
        vm.goBack()
        vm.skipBasics()
        vm.submit(PlayerIntent.Fold(seatIndex = 0))

        assertEquals(true, vm.state.value.completed)
        assertEquals(1, achievements.recordCalls)
    }

    @Test
    fun completion_marksTutorialBannerDismissed() = runUnitTest {
        val cache = TutorialAppCacheFake()
        val vm = buildVm(appCache = cache)

        runScriptToCompletion(vm)

        assertEquals(true, cache.get().tutorialBannerDismissed)
    }

    // -------------------- scaffolding --------------------

    private fun buildVm(
        achievements: AchievementRepository = TutorialAchievementsFake(),
        appCache: AppCache = TutorialAppCacheFake(),
    ): TutorialViewModel = TutorialViewModel(
        achievementRepository = achievements,
        appCache = appCache,
    )

    private fun runScriptToCompletion(vm: TutorialViewModel) {
        // One extra advance() past the last index triggers the completion
        // branch; each advance is processed synchronously on the
        // UnconfinedTestDispatcher so the suspending grant call drains
        // inline without needing advanceUntilIdle.
        repeat(script.size) { vm.advance() }
    }

    private fun firstAdvanceableStep(): Pair<PlayerIntent, Int> {
        val index = script.indexOfFirst { it.advanceOn != null }
        check(index >= 0) { "test fixture assumes at least one step gates on PlayerIntent" }
        val predicate = script[index].advanceOn!!
        val candidate = matchingIntent(predicate)
        return candidate to index
    }

    private fun matchingIntent(predicate: (PlayerIntent) -> Boolean): PlayerIntent {
        val candidates = listOf<PlayerIntent>(
            PlayerIntent.Fold(seatIndex = 0),
            PlayerIntent.Check(seatIndex = 0),
            PlayerIntent.Call(seatIndex = 0),
            PlayerIntent.Raise(seatIndex = 0, totalAmountThisStreet = 200L),
            PlayerIntent.Raise(seatIndex = 0, totalAmountThisStreet = 600L),
            PlayerIntent.Bet(seatIndex = 0, amount = 200L),
            PlayerIntent.AllIn(seatIndex = 0),
        )
        return candidates.firstOrNull(predicate)
            ?: error("no PlayerIntent in the candidate set satisfied the predicate")
    }

    private fun nonMatchingIntent(matching: PlayerIntent): PlayerIntent =
        if (matching is PlayerIntent.Fold) PlayerIntent.Check(seatIndex = 0)
        else PlayerIntent.Fold(seatIndex = 0)

    private fun aTutorialEarnedAchievement(): EarnedAchievement = EarnedAchievement(
        achievement = AllAchievementsById.getValue(AchievementId.TUTORIAL_COMPLETE),
        earnedAtEpochMs = 1_700_000_000_000,
    )
}

private class TutorialAchievementsFake(
    private val tutorialEarned: EarnedAchievement? = null,
    private val throwOnRecord: Boolean = false,
) : AchievementRepository {
    private val state = MutableStateFlow(AchievementProgress.Empty)
    var recordCalls: Int = 0
        private set

    override fun observeProgress(): Flow<AchievementProgress> = state
    override suspend fun getProgress(): AchievementProgress = state.value

    override suspend fun recordHand(
        summary: HandResultSummary,
        context: AchievementHandContext,
    ): List<EarnedAchievement> = emptyList()

    override suspend fun recordTutorialComplete(): EarnedAchievement? {
        recordCalls++
        if (throwOnRecord) error("simulated grant failure")
        return tutorialEarned
    }

    override suspend fun sync(): Result<Unit> = Result.success(Unit)

    override suspend fun deleteAll() {
        state.value = AchievementProgress.Empty
    }
}

private class TutorialAppCacheFake(initial: AppData = AppData()) : AppCache {
    private val state = MutableStateFlow(initial)
    override val updates: Flow<AppData> = state
    override suspend fun get(): AppData = state.value
    override suspend fun set(value: AppData) { state.value = value }
    override suspend fun clear() { state.value = AppData() }
}
