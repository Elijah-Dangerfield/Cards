package com.dangerfield.cards.server.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

data class StandingTable(
    val code: String,
    val ownerUserId: UserId?,
    val name: String,
    val buyIn: Long,
    val maxSeats: Int,
    val weekday: DayOfWeek,
    val localTime: LocalTime,
    val timeZone: TimeZone,
    val durationMinutes: Int = 120,
    val feltProductId: String? = null,
    val cardBackProductId: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archivedAt: Instant? = null,
) {
    /** A table whose owner deleted their account is closed for good; its code stays reserved. */
    val isArchived: Boolean get() = archivedAt != null || ownerUserId == null
}

data class StandingTablePatch(
    val name: String? = null,
    val buyIn: Long? = null,
    val maxSeats: Int? = null,
    val weekday: DayOfWeek? = null,
    val localTime: LocalTime? = null,
    val timeZone: TimeZone? = null,
    val durationMinutes: Int? = null,
    val updatedAt: Instant,
)
