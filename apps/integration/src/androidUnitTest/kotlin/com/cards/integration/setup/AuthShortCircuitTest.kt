package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.SettableAuthGate
import com.dangerfield.cards.libraries.core.AuthReason
import com.dangerfield.cards.libraries.core.AuthVerdict
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * **Doomed calls never reach the wire.** Against the real server stack, a
 * Blocked auth verdict short-circuits join-room into the honest outcome
 * (connection problem for Offline) and the server never sees the request —
 * the regression guard for the phantom-401 bug this system exists to kill.
 * The wire proof is the server's own `alreadyJoined` flag: had the blocked
 * join landed, the post-unblock join would report a re-join. Verdict
 * production is pinned in AuthGateImplTest; this pins the wire truth.
 */
class AuthShortCircuitTest : IntegrationTest() {

    @Test
    fun blockedOffline_join_readsAsNetworkError_andNeverHitsTheServer() = integration {
        val host = client()
        val code = assertIs<CreateRoomOutcome.Success>(host.repository.createRoom()).room.code

        val gate = SettableAuthGate(AuthVerdict.Blocked(AuthReason.Offline))
        val user = client(authGate = gate)

        assertIs<JoinRoomOutcome.NetworkError>(user.repository.joinRoom(code))

        gate.next = AuthVerdict.Ready
        val joined = assertIs<JoinRoomOutcome.Success>(user.repository.joinRoom(code))
        assertFalse(joined.alreadyJoined, "the short-circuited join must never have reached the server")
    }
}
