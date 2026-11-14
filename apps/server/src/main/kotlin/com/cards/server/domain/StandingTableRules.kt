package com.dangerfield.cards.server.domain

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

object StandingTableRules {
    fun normalizedName(name: String): String = name.trim().replace(Regex("\\s+"), " ")

    fun invalidField(name: String, buyIn: Long, maxSeats: Int, weekday: Int,
                     localTime: String, timeZone: String, durationMinutes: Int): String? {
        if (name.any { it.isISOControl() } || normalizedName(name).length !in 1..32) return "name"
        if (buyIn !in RoomSettings.MIN_BUY_IN..RoomSettings.MAX_BUY_IN) return "buyIn"
        if (maxSeats !in RoomSettings.MIN_SEATS..RoomSettings.MAX_SEATS) return "maxSeats"
        if (weekday !in 1..7) return "weekday"
        val time = Catching { LocalTime.parse(localTime) }.getOrNull()
        if (!Regex("[0-2][0-9]:[0-5][0-9]").matches(localTime) || time == null || time.minute % 15 != 0) return "localTime"
        if (timeZone !in TimeZone.availableZoneIds || Catching { TimeZone.of(timeZone) }.getOrNull() == null) return "timeZone"
        if (durationMinutes !in 30..480) return "durationMinutes"
        return null
    }
}
