package com.dangerfield.cards.libraries.social.impl

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.social.RecentOpponentProfile
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentOpponentsRepositoryImplTest : CoroutineTest() {

    @Test
    fun refresh_resolves_ids_and_preserves_server_order() = runUnitTest {
        val api = FakeSocialApi(
            recentIds = listOf("b", "a", "c"),
            profiles = listOf(publicProfile("a"), publicProfile("b"), publicProfile("c")),
        )
        val repo = RecentOpponentsRepositoryImpl(api)

        repo.refresh(limit = 5)

        assertEquals(listOf("b", "a", "c"), repo.observe().first().map { it.id })
        assertEquals(5, api.lastLimit)
    }

    @Test
    fun refresh_maps_all_public_fields() = runUnitTest {
        val api = FakeSocialApi(
            recentIds = listOf("a"),
            profiles = listOf(
                PublicProfileDto(
                    id = "a",
                    displayName = "Patrice",
                    avatarEmoji = "🦁",
                    avatarBackgroundColor = "#C658E4",
                ),
            ),
        )
        val repo = RecentOpponentsRepositoryImpl(api)

        repo.refresh()

        assertEquals(
            RecentOpponentProfile(
                id = "a",
                displayName = "Patrice",
                avatarEmoji = "🦁",
                avatarBackgroundColorHex = "#C658E4",
            ),
            repo.observe().first().single(),
        )
    }

    @Test
    fun refresh_drops_ids_that_do_not_resolve() = runUnitTest {
        val api = FakeSocialApi(
            recentIds = listOf("a", "missing", "c"),
            profiles = listOf(publicProfile("a"), publicProfile("c")),
        )
        val repo = RecentOpponentsRepositoryImpl(api)

        repo.refresh()

        assertEquals(listOf("a", "c"), repo.observe().first().map { it.id })
    }

    @Test
    fun refresh_with_no_recent_ids_yields_empty_without_resolving() = runUnitTest {
        val api = FakeSocialApi(recentIds = emptyList())
        val repo = RecentOpponentsRepositoryImpl(api)

        repo.refresh()

        assertEquals(emptyList<RecentOpponentProfile>(), repo.observe().first())
        assertEquals(0, api.resolveCallCount)
    }

    @Test
    fun refresh_failure_keeps_last_good_value() = runUnitTest {
        val api = FakeSocialApi(
            recentIds = listOf("a"),
            profiles = listOf(publicProfile("a")),
        )
        val repo = RecentOpponentsRepositoryImpl(api)
        repo.refresh()

        api.recentError = RuntimeException("network down")
        repo.refresh()

        assertEquals(listOf("a"), repo.observe().first().map { it.id })
    }
}
