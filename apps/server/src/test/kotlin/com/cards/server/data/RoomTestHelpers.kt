package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.domain.CreateResult
import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.UserId

/**
 * Test-only helper: collapses [RoomService.create]'s sealed result back
 * to a plain [Room] for the (overwhelming) majority of cases that don't
 * care about the new `TooManyRooms` branch. Tests that DO care about
 * the cap call `create()` directly and pattern-match.
 *
 * Errors loudly when the cap kicks in unexpectedly — keeps test
 * intent obvious instead of letting a silent null slip through.
 */
internal suspend fun RoomService.createOrFail(
    hostUserId: UserId,
    hostName: String,
    maxSeats: Int = RoomService.MAX_SEATS,
): Room = when (val outcome = create(hostUserId, hostName, maxSeats)) {
    is CreateResult.Success -> outcome.room
    is CreateResult.TooManyRooms ->
        error("createOrFail expected Success, got TooManyRooms(active=${outcome.activeCount})")
}
