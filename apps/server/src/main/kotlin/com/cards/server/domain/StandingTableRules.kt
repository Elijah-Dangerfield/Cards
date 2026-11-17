package com.dangerfield.cards.server.domain

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

object StandingTableRules {
    fun normalizedName(name: String): String = name.trim().replace(Regex("\\s+"), " ")

    fun invalidField(name: String, buyIn: Long, maxSeats: Int, weekday: Int,
                     localTime: String, timeZone: String, durationMinutes: Int): String? {
        if (name.any { it.isISOControl() } || hasHiddenFormatting(name) || normalizedName(name).length !in 1..32) return "name"
        if (buyIn !in RoomSettings.MIN_BUY_IN..RoomSettings.MAX_BUY_IN) return "buyIn"
        if (maxSeats !in RoomSettings.MIN_SEATS..RoomSettings.MAX_SEATS) return "maxSeats"
        if (weekday !in 1..7) return "weekday"
        val time = Catching { LocalTime.parse(localTime) }.getOrNull()
        if (!Regex("[0-2][0-9]:[0-5][0-9]").matches(localTime) || time == null || time.minute % 15 != 0) return "localTime"
        if (timeZone !in TimeZone.availableZoneIds || Catching { TimeZone.of(timeZone) }.getOrNull() == null) return "timeZone"
        if (durationMinutes !in 30..480) return "durationMinutes"
        return null
    }

    // The name is shown on a public preview, so bidi overrides and zero-width characters
    // could disguise it. A zero-width joiner is fine inside an emoji sequence.
    private fun hasHiddenFormatting(name: String): Boolean {
        val codePoints = name.codePoints().toArray()
        return codePoints.indices.any { i ->
            val cp = codePoints[i]
            Character.getType(cp) == Character.FORMAT.toInt() &&
                !(cp == ZERO_WIDTH_JOINER && joinsEmoji(codePoints.getOrNull(i - 1), codePoints.getOrNull(i + 1)))
        }
    }

    private fun joinsEmoji(before: Int?, after: Int?): Boolean =
        before != null && after != null && Character.getType(after) == Character.OTHER_SYMBOL.toInt() &&
            (before == EMOJI_PRESENTATION || Character.getType(before).let {
                it == Character.OTHER_SYMBOL.toInt() || it == Character.MODIFIER_SYMBOL.toInt()
            })

    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val EMOJI_PRESENTATION = 0xFE0F
}
