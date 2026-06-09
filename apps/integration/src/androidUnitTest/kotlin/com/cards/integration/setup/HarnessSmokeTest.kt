package com.cards.integration.setup

import com.cards.integration.helpers.InProcessServer
import com.cards.integration.helpers.TestClient
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The harness's own smoke test: a real client authenticates and creates a room
 * against the real in-process server. Proves [InProcessServer] + [TestClient] +
 * the JWT wiring work before the multi-client golden path leans on them.
 */
class HarnessSmokeTest {

    @Test
    fun realClient_createsRoom_againstRealServer() = runBlocking<Unit> {
        InProcessServer().use { server ->
            val client = TestClient(server.baseUrl)
            val outcome = client.repository.createRoom()
            assertTrue(outcome is CreateRoomOutcome.Success, "expected Success, got $outcome")
        }
    }
}
