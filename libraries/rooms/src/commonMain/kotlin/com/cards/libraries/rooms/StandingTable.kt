package com.dangerfield.cards.libraries.rooms

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

data class StandingTable(
    val code: String,
    val name: String,
    val ownerUserId: String,
    val ownerDisplayName: String,
    val isOwner: Boolean,
    val buyIn: Long,
    val maxSeats: Int,
    val weekday: DayOfWeek,
    val localTime: LocalTime,
    val timeZone: TimeZone,
    val durationMinutes: Int,
    val nextStartsAt: Instant,
    val regularCount: Int,
    val live: StandingTableLive? = null,
    val archived: Boolean = false,
    val feltProductId: String? = null,
    val cardBackProductId: String? = null,
)

data class StandingTableLive(val seatedHumans: Int, val status: RoomStatus)

data class StandingTableSummary(
    val name: String,
    val weekday: DayOfWeek,
    val localTime: LocalTime,
    val timeZone: TimeZone,
    val nextStartsAt: Instant,
)

data class StandingTableDraft(
    val name: String,
    val buyIn: Long,
    val maxSeats: Int,
    val weekday: DayOfWeek,
    val localTime: LocalTime,
    val timeZone: TimeZone,
    val durationMinutes: Int = 120,
    val feltProductId: String? = null,
    val cardBackProductId: String? = null,
)
