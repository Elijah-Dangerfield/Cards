package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.rooms.RoomStatus
import com.dangerfield.cards.libraries.rooms.StandingTable
import com.dangerfield.cards.libraries.rooms.StandingTableDraft
import com.dangerfield.cards.libraries.rooms.StandingTableLive
import com.dangerfield.cards.libraries.rooms.StandingTableSummary
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.Serializable
import kotlin.time.Instant

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
    val archived: Boolean = false,
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
data class StandingTablesResponseDto(val schemaVersion: Int = 1, val tables: List<StandingTableDto>)

@Serializable
data class CreateStandingTableRequestDto(
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
data class UpdateStandingTableRequestDto(
    val name: String,
    val buyIn: Long,
    val maxSeats: Int,
    val weekday: Int,
    val localTime: String,
    val timeZone: String,
    val durationMinutes: Int,
)

internal fun StandingTableDto.toDomain() = StandingTable(
    code = code,
    name = name,
    ownerUserId = ownerUserId,
    ownerDisplayName = ownerDisplayName,
    isOwner = isOwner,
    buyIn = buyIn,
    maxSeats = maxSeats,
    weekday = DayOfWeek(weekday),
    localTime = LocalTime.parse(localTime),
    timeZone = TimeZone.of(timeZone),
    durationMinutes = durationMinutes,
    nextStartsAt = Instant.parse(nextStartsAt),
    regularCount = regularCount,
    live = live?.let {
        StandingTableLive(it.seatedHumans, when (it.status) {
            "lobby" -> RoomStatus.Lobby
            "playing" -> RoomStatus.Playing
            else -> RoomStatus.Unknown
        })
    },
    archived = archived,
    feltProductId = feltProductId,
    cardBackProductId = cardBackProductId,
)

internal fun StandingTableSummaryDto.toDomain() = StandingTableSummary(
    name = name,
    weekday = DayOfWeek(weekday),
    localTime = LocalTime.parse(localTime),
    timeZone = TimeZone.of(timeZone),
    nextStartsAt = Instant.parse(nextStartsAt),
)

internal fun StandingTableDraft.toCreateRequest() = CreateStandingTableRequestDto(
    name, buyIn, maxSeats, weekday.isoDayNumber,
    "${localTime.hour.toString().padStart(2, '0')}:${localTime.minute.toString().padStart(2, '0')}",
    timeZone.id, durationMinutes, feltProductId, cardBackProductId,
)

internal fun StandingTableDraft.toUpdateRequest() = toCreateRequest().let {
    UpdateStandingTableRequestDto(it.name, it.buyIn, it.maxSeats, it.weekday, it.localTime, it.timeZone, it.durationMinutes)
}
