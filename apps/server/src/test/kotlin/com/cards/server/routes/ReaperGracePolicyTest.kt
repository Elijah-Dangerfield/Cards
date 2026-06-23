package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomStatus
import com.dangerfield.cards.server.domain.RoomVisibility
import com.dangerfield.cards.server.domain.UserId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The seat-reaper grace policy: a forming public/open table frees an abandoned
 * seat fast (so a quit Searching screen leaves no ghost), while a live hand or a
 * private room keeps the full window (mid-hand reconnect is sacred).
 */
@OptIn(ExperimentalTime::class)
class ReaperGracePolicyTest {

    private fun room(visibility: RoomVisibility, status: RoomStatus) = Room(
        code = "ABCDEF",
        hostUserId = UserId(UUID.randomUUID()),
        createdAt = Instant.fromEpochMilliseconds(0),
        maxSeats = 6,
        status = status,
        members = emptyList(),
        visibility = visibility,
    )

    @Test
    fun formingPublicLobby_getsTheShortGrace() {
        assertEquals(
            FORMING_PUBLIC_REAPER_GRACE,
            effectiveReaperGrace(room(RoomVisibility.Public, RoomStatus.Lobby), DEFAULT_REAPER_GRACE),
        )
    }

    @Test
    fun formingOpenLobby_getsTheShortGrace() {
        assertEquals(
            FORMING_PUBLIC_REAPER_GRACE,
            effectiveReaperGrace(room(RoomVisibility.Open, RoomStatus.Lobby), DEFAULT_REAPER_GRACE),
        )
    }

    @Test
    fun publicPlaying_getsTheFullGrace() {
        assertEquals(
            DEFAULT_REAPER_GRACE,
            effectiveReaperGrace(room(RoomVisibility.Public, RoomStatus.Playing), DEFAULT_REAPER_GRACE),
        )
    }

    @Test
    fun privateLobby_getsTheFullGrace() {
        assertEquals(
            DEFAULT_REAPER_GRACE,
            effectiveReaperGrace(room(RoomVisibility.Private, RoomStatus.Lobby), DEFAULT_REAPER_GRACE),
        )
    }

    @Test
    fun missingRoom_getsTheFullGrace() {
        assertEquals(DEFAULT_REAPER_GRACE, effectiveReaperGrace(null, DEFAULT_REAPER_GRACE))
    }
}
