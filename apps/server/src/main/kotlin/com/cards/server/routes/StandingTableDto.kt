package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomStatus
import com.dangerfield.cards.server.domain.StandingTable
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.nextStart
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class CreateStandingTableRequest(
    val name: String,
    val buyIn: Long,
    val maxSeats: Int,
    val weekday: Int,
    val localTime: String,
    val timeZone: String,
    val durationMinutes: Int = 120,
    val feltProductId: String? = null,
    val cardBackProductId: String? = null,
)

@Serializable
data class PatchStandingTableRequest(
    val name: String? = null,
    val buyIn: Long? = null,
    val maxSeats: Int? = null,
    val weekday: Int? = null,
    val localTime: String? = null,
    val timeZone: String? = null,
    val durationMinutes: Int? = null,
)

@Serializable
data class StandingTableDto(
    val schemaVersion: Int = 1,
    val code: String,
    val name: String,
    val ownerUserId: String,
    val ownerDisplayName: String,
    val isOwner: Boolean,
    val buyIn: Long,
    val maxSeats: Int,
    val weekday: Int,
    val localTime: String,
    val timeZone: String,
    val durationMinutes: Int,
    val nextStartsAt: String,
    val regularCount: Int,
    val live: StandingTableLiveDto? = null,
    val archived: Boolean,
    val feltProductId: String? = null,
    val cardBackProductId: String? = null,
)

@Serializable
data class StandingTableLiveDto(val seatedHumans: Int, val status: String)

@Serializable
data class StandingTableSummaryDto(
    val name: String,
    val weekday: Int,
    val localTime: String,
    val timeZone: String,
    val nextStartsAt: String,
)

@Serializable
data class StandingTablesResponse(val schemaVersion: Int = 1, val tables: List<StandingTableDto>)

internal fun StandingTable.summaryDto(now: Instant) = StandingTableSummaryDto(
    name = name,
    weekday = weekday.ordinal + 1,
    localTime = localTime.toString(),
    timeZone = timeZone.id,
    nextStartsAt = nextStart(this, now).toString(),
)

internal fun StandingTable.toDto(
    viewer: UserId,
    ownerName: String,
    regulars: Int,
    room: Room?,
    now: Instant,
) = StandingTableDto(
    code = code,
    name = name,
    ownerUserId = ownerUserId.value.toString(),
    ownerDisplayName = ownerName,
    isOwner = viewer == ownerUserId,
    buyIn = buyIn,
    maxSeats = maxSeats,
    weekday = weekday.ordinal + 1,
    localTime = localTime.toString(),
    timeZone = timeZone.id,
    durationMinutes = durationMinutes,
    nextStartsAt = nextStart(this, now).toString(),
    regularCount = regulars,
    live = room?.let {
        StandingTableLiveDto(
            seatedHumans = it.members.count { member -> !member.isBot && member.isConnected },
            status = if (it.status == RoomStatus.Playing) "playing" else "lobby",
        )
    },
    archived = archivedAt != null,
    feltProductId = feltProductId,
    cardBackProductId = cardBackProductId,
)
