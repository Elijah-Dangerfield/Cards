package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The harness's own smoke test: a real client authenticates and creates a room
 * against the real in-process server. Proves the helpers + JWT wiring work before
 * the journey tests lean on them.
 */
class HarnessSmokeTest : IntegrationTest() {

    @Test
    fun realClient_createsRoom_againstRealServer() = integration {
        val outcome = client().repository.createRoom()
        assertTrue(outcome is CreateRoomOutcome.Success, "expected Success, got $outcome")
    }
}
