package com.dangerfield.cards.libraries.navigation

import kotlinx.datetime.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals

class WeeklyRuleTest {
    @Test
    fun usesCalendarWeekdayCodesForEveryDay() {
        val expected = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
        DayOfWeek.entries.zip(expected).forEach { (day, code) ->
            assertEquals("FREQ=WEEKLY;BYDAY=$code", weeklyRule(day))
        }
    }
}
