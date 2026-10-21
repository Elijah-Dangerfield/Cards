package com.dangerfield.cards.libraries.social.impl

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.social.Rivalry
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RivalryRepositoryImplTest : CoroutineTest() {

    @Test
    fun refresh_resolvesOpponents_andKeepsTheServerOrder() = runUnitTest {
        val api = FakeSocialApi(
            rivalries = listOf(record("b"), record("a")),
            profiles = listOf(publicProfile("a"), publicProfile("b")),
        )
        val repo = RivalryRepositoryImpl(api)

        repo.refresh(limit = 3)

        assertEquals(listOf("b", "a"), repo.observe().first().map { it.opponentId })
        assertEquals(3, api.lastRivalryLimit)
    }

    @Test
    fun refresh_carriesTheWholeScoreline() = runUnitTest {
        val api = FakeSocialApi(
            rivalries = listOf(
                RivalryDto(
                    opponentId = "a",
                    handsTogether = 31,
                    handsWon = 11,
                    opponentHandsWon = 14,
                    biggestPotWon = 4_200,
                    sessionsTogether = 4,
                ),
            ),
            profiles = listOf(
                PublicProfileDto(
                    id = "a",
                    displayName = "Mark",
                    avatarEmoji = "🦅",
                    avatarBackgroundColor = "#5B8C7A",
                ),
            ),
        )
        val repo = RivalryRepositoryImpl(api)

        repo.refresh()

        assertEquals(
            Rivalry(
                opponentId = "a",
                displayName = "Mark",
                avatarEmoji = "🦅",
                avatarBackgroundColorHex = "#5B8C7A",
                handsTogether = 31,
                handsWon = 11,
                opponentHandsWon = 14,
                biggestPotWon = 4_200,
                sessionsTogether = 4,
            ),
            repo.observe().first().single(),
        )
    }

    @Test
    fun standing_readsTheScorelineNotTheOrder() = runUnitTest {
        val api = FakeSocialApi(
            rivalries = listOf(
                record("ahead", handsWon = 9, opponentHandsWon = 2),
                record("behind", handsWon = 2, opponentHandsWon = 9),
                record("even", handsWon = 5, opponentHandsWon = 5),
            ),
            profiles = listOf(publicProfile("ahead"), publicProfile("behind"), publicProfile("even")),
        )
        val repo = RivalryRepositoryImpl(api)

        repo.refresh()

        assertEquals(
            listOf(Rivalry.Standing.Ahead, Rivalry.Standing.Behind, Rivalry.Standing.Even),
            repo.observe().first().map { it.standing },
        )
    }

    @Test
    fun refresh_dropsAnOpponentItCannotName() = runUnitTest {
        // A scoreline against a blank avatar is worse than one fewer card.
        val api = FakeSocialApi(
            rivalries = listOf(record("a"), record("deleted")),
            profiles = listOf(publicProfile("a")),
        )
        val repo = RivalryRepositoryImpl(api)

        repo.refresh()

        assertEquals(listOf("a"), repo.observe().first().map { it.opponentId })
    }

    @Test
    fun refresh_withNoRecords_skipsTheProfileResolve() = runUnitTest {
        val api = FakeSocialApi(rivalries = emptyList())
        val repo = RivalryRepositoryImpl(api)

        repo.refresh()

        assertTrue(repo.observe().first().isEmpty())
        assertEquals(0, api.resolveCallCount)
    }

    @Test
    fun refresh_failure_keepsTheLastGoodValue() = runUnitTest {
        val api = FakeSocialApi(
            rivalries = listOf(record("a")),
            profiles = listOf(publicProfile("a")),
        )
        val repo = RivalryRepositoryImpl(api)
        repo.refresh()

        api.rivalriesError = RuntimeException("network down")
        repo.refresh()

        assertEquals(listOf("a"), repo.observe().first().map { it.opponentId })
    }

    private fun record(
        opponentId: String,
        handsWon: Int = 4,
        opponentHandsWon: Int = 2,
    ) = RivalryDto(
        opponentId = opponentId,
        handsTogether = handsWon + opponentHandsWon,
        handsWon = handsWon,
        opponentHandsWon = opponentHandsWon,
        biggestPotWon = 1_000,
        sessionsTogether = 2,
    )
}
