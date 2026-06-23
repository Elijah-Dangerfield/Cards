package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomClosedListener
import me.tatarka.inject.annotations.Inject
import org.slf4j.LoggerFactory
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The one [RoomClosedListener]: when a room is torn down, end its game session so
 * nothing tied to the room outlives it. [GameSessionRegistry.end] cancels the
 * per-session bot + turn-timer coroutines, drops the in-memory session, and
 * deletes the durable snapshot — without this, every table that ever dealt a
 * hand leaked a live session (and a recycled room code could rehydrate a
 * stranger's hand from the orphaned snapshot).
 *
 * Best-effort: a teardown failure is logged, never thrown — a room is already
 * gone from the registry by the time we run, so failing here would only strand a
 * session, not corrupt room state. (Phase 4 extends this to cash every seated
 * human's stack back to their wallet *before* ending the session.)
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class RoomTeardownCoordinator(
    private val gameSessions: GameSessionRegistry,
) : RoomClosedListener {

    private val log = LoggerFactory.getLogger(RoomTeardownCoordinator::class.java)

    override suspend fun onRoomClosed(room: Room) {
        Catching { gameSessions.end(room.code) }
            .onFailure { log.warn("Failed to end session for closed room ${room.code}", it) }
    }
}
