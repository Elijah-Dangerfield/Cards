package com.dangerfield.cards.server.domain

/**
 * Achievements whose unlock event can legitimately be reported by the
 * client and result in a server-side inventory grant.
 *
 * **Contract:** the server is authoritative for any achievement it can
 * witness directly — hand resolution, league season-end, anything driven
 * off server-state. Those achievements MUST NOT appear in
 * [clientGrantable]; if they did, a malicious client could self-grant them
 * via `POST /v1/me/grants/achievement/{id}` with their own auth token.
 * They go in [serverWitnessed] so the grant route returns 403, separating
 * "I know this id and refuse" from "I have no idea what this id is" (the
 * latter stays 204 for graceful client/server version skew).
 *
 * **Why client-grantable at all:** a handful of single-player achievements
 * (pot-size, comeback) are tracked purely on-device today since hand
 * resolution still runs in the client. The client already detects the
 * unlock at the moment of resolution; surfacing that as a 200/204/403 is
 * cheaper than rebuilding the server's view of those counters. When hand
 * resolution moves server-side (Phase 4.2), the affected ids migrate from
 * [clientGrantable] to [serverWitnessed].
 *
 * Idempotency stays with [InventoryRepository.recordEarnedGrant] — a
 * duplicate POST for the same achievement is a no-op.
 */
class ClientGrantableAchievements(
    private val clientGrantable: Map<String, String>,
    private val serverWitnessed: Set<String> = emptySet(),
) {

    fun resolve(achievementId: String): Resolution = when {
        achievementId in clientGrantable ->
            Resolution.Grant(clientGrantable.getValue(achievementId))
        achievementId in serverWitnessed -> Resolution.ServerWitnessed
        else -> Resolution.Unknown
    }

    sealed interface Resolution {
        data class Grant(val productId: String) : Resolution
        data object ServerWitnessed : Resolution
        data object Unknown : Resolution
    }

    companion object {
        val Default: ClientGrantableAchievements = ClientGrantableAchievements(
            clientGrantable = mapOf(
                "POT_5000" to "title_pot_magnet",
                "COMEBACK_FROM_5BB" to "title_short_stack_hero",
                "DONT_CALL_IT_COMEBACK" to "cardback_comeback_kid",
                "BOT_WHISPERER" to "title_bot_whisperer",
                "BUST_DEALT_5" to "emotes_eliminator",
                "TRIPLE_UP" to "emotes_baller",
                "NO_BUST_100" to "emotes_iron_stack",
                "WIN_BY_FOLD_10" to "emotes_convincer",
                "GOOD_FOLD_25" to "emotes_disciplined",
                "HANDS_1000" to "emotes_grinder",
                "DOUBLE_UP" to "emotes_doubler",
                "CHALLENGING_10_WINS" to "emotes_tactician",
                "REACH_LEVEL_25" to "title_felt_veteran",
            ),
            // Multiplayer-mode achievements live here so the client grant
            // route returns 403, not 204. Once Phase 4.2 server-authoritative
            // hand resolution lands, these grants fire server-side and the
            // 403 keeps a malicious client from self-granting via the
            // existing POST endpoint.
            serverWitnessed = setOf(
                "FIRST_BUST_DEALT_MP",
                "BUST_DEALT_5_MP",
            ),
        )
    }
}
