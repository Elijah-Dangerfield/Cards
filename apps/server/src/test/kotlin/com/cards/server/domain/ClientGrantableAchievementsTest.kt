package com.dangerfield.cards.server.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct unit pins for the [ClientGrantableAchievements] policy.
 *
 * [`GrantsRoutesTest`] covers the resolver transitively through the
 * `POST /v1/me/grants/achievement/{id}` route, but the policy itself is
 * load-bearing for the **security split** between client-grantable and
 * server-witnessed ids: if a multiplayer achievement ever silently
 * graduated from `serverWitnessed` to `clientGrantable` (or fell out
 * entirely), a malicious client could self-grant via the existing
 * endpoint. Pinning [Resolution] branches independently means a future
 * edit to the `Default` map gets caught at this test before it gets
 * caught at the route test (or, worse, in production).
 */
class ClientGrantableAchievementsTest {

    @Test
    fun resolve_returnsGrant_forClientGrantableId() {
        val policy = ClientGrantableAchievements(
            clientGrantable = mapOf("FOO" to "product_foo"),
            serverWitnessed = emptySet(),
        )

        val result = policy.resolve("FOO")

        assertTrue(result is ClientGrantableAchievements.Resolution.Grant)
        assertEquals("product_foo", result.productId)
    }

    @Test
    fun resolve_returnsServerWitnessed_forServerWitnessedId() {
        val policy = ClientGrantableAchievements(
            clientGrantable = emptyMap(),
            serverWitnessed = setOf("MP_FOO"),
        )

        val result = policy.resolve("MP_FOO")

        assertEquals(ClientGrantableAchievements.Resolution.ServerWitnessed, result)
    }

    @Test
    fun resolve_returnsUnknown_forIdNotInEitherSet() {
        // Forward-compat split: client/server skew (a renamed or
        // deleted id from a newer client) returns Unknown so the route
        // can 204 instead of 403. Only ids the server *deliberately*
        // refuses get the 403/ServerWitnessed treatment.
        val policy = ClientGrantableAchievements(
            clientGrantable = mapOf("FOO" to "product_foo"),
            serverWitnessed = setOf("MP_FOO"),
        )

        val result = policy.resolve("UNKNOWN_ID")

        assertEquals(ClientGrantableAchievements.Resolution.Unknown, result)
    }

    @Test
    fun resolve_clientGrantableTakesPrecedence_overAccidentalServerWitnessedOverlap() {
        // Defensive: if a future edit to `Default` accidentally adds the
        // same id to both sets, the client-grantable branch must win —
        // otherwise a bot-mode achievement that suddenly appears in
        // serverWitnessed would 403 for every legitimate client claim.
        // The contract here is "clientGrantable is the affirmative
        // allowlist; serverWitnessed is the explicit deny-list for the
        // remainder."
        val policy = ClientGrantableAchievements(
            clientGrantable = mapOf("FOO" to "product_foo"),
            serverWitnessed = setOf("FOO"),
        )

        val result = policy.resolve("FOO")

        assertTrue(result is ClientGrantableAchievements.Resolution.Grant)
        assertEquals("product_foo", result.productId)
    }

    @Test
    fun resolve_isCaseSensitive() {
        // Ids are normalized client-side to uppercase enum names; the
        // server-side policy does no case folding. Pin this so a future
        // "let me make it lenient" refactor doesn't silently widen the
        // attack surface (the route then accepting `pot_5000` lowercase
        // would let a probing client bypass any future id-prefix
        // filter).
        val policy = ClientGrantableAchievements(
            clientGrantable = mapOf("POT_5000" to "title_pot_magnet"),
            serverWitnessed = emptySet(),
        )

        assertEquals(
            ClientGrantableAchievements.Resolution.Unknown,
            policy.resolve("pot_5000"),
        )
        assertTrue(policy.resolve("POT_5000") is ClientGrantableAchievements.Resolution.Grant)
    }

    @Test
    fun defaultPolicy_resolvesEveryServerWitnessedMpAchievement() {
        // Pins the entire `serverWitnessed` set in the Default policy.
        // Any future MP-mode achievement added to the registry MUST land
        // here (so the client can't self-grant it before Phase 4.2
        // server-witnessed gameplay lands). A failing test here is the
        // loud signal that the registry got a new `*_MP` id that
        // wasn't added to the policy.
        val expectedServerWitnessed = setOf(
            "FIRST_BUST_DEALT_MP",
            "BUST_DEALT_5_MP",
            "FIRST_HAND_MP",
            "HANDS_100_MP",
            "WIN_BY_FOLD_10_MP",
            "DOUBLE_UP_MP",
            "TRIPLE_UP_MP",
            "POT_5000_MP",
        )

        expectedServerWitnessed.forEach { id ->
            assertEquals(
                ClientGrantableAchievements.Resolution.ServerWitnessed,
                ClientGrantableAchievements.Default.resolve(id),
                "Default policy must classify $id as ServerWitnessed",
            )
        }
    }

    @Test
    fun defaultPolicy_resolvesKnownBotAchievementsToTheirProductIds() {
        // Sample the bot-mode achievements + their cosmetic grants.
        // A typo in the productId on the right-hand side would silently
        // grant the wrong cosmetic — caught here, not by a hand-coded
        // route test that happens to use the same id.
        val expected = mapOf(
            "POT_5000" to "title_pot_magnet",
            "DONT_CALL_IT_COMEBACK" to "cardback_comeback_kid",
            "BOT_WHISPERER" to "title_bot_whisperer",
            "REACH_LEVEL_25" to "title_felt_veteran",
        )

        expected.forEach { (achievementId, productId) ->
            val result = ClientGrantableAchievements.Default.resolve(achievementId)
            assertTrue(
                result is ClientGrantableAchievements.Resolution.Grant,
                "Default policy should grant $achievementId",
            )
            assertEquals(
                productId,
                result.productId,
                "Default policy mapping for $achievementId",
            )
        }
    }

    @Test
    fun defaultPolicy_returnsUnknown_forArbitraryId() {
        assertEquals(
            ClientGrantableAchievements.Resolution.Unknown,
            ClientGrantableAchievements.Default.resolve("NOT_A_REAL_ACHIEVEMENT_ID"),
        )
    }
}
