package com.dangerfield.cards.libraries.ui.components.room

import androidx.compose.runtime.Composable
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.standing_table_home_date
import cards.libraries.resources.generated.resources.standing_table_home_tomorrow
import cards.libraries.resources.generated.resources.standing_table_home_tonight
import cards.libraries.resources.generated.resources.standing_table_schedule_label
import cards.libraries.resources.generated.resources.ui_schedule_am
import cards.libraries.resources.generated.resources.ui_schedule_april
import cards.libraries.resources.generated.resources.ui_schedule_august
import cards.libraries.resources.generated.resources.ui_schedule_december
import cards.libraries.resources.generated.resources.ui_schedule_february
import cards.libraries.resources.generated.resources.ui_schedule_friday
import cards.libraries.resources.generated.resources.ui_schedule_january
import cards.libraries.resources.generated.resources.ui_schedule_july
import cards.libraries.resources.generated.resources.ui_schedule_june
import cards.libraries.resources.generated.resources.ui_schedule_march
import cards.libraries.resources.generated.resources.ui_schedule_may
import cards.libraries.resources.generated.resources.ui_schedule_monday
import cards.libraries.resources.generated.resources.ui_schedule_november
import cards.libraries.resources.generated.resources.ui_schedule_october
import cards.libraries.resources.generated.resources.ui_schedule_pm
import cards.libraries.resources.generated.resources.ui_schedule_saturday
import cards.libraries.resources.generated.resources.ui_schedule_september
import cards.libraries.resources.generated.resources.ui_schedule_sunday
import cards.libraries.resources.generated.resources.ui_schedule_thursday
import cards.libraries.resources.generated.resources.ui_schedule_tuesday
import cards.libraries.resources.generated.resources.ui_schedule_wednesday
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

fun weekdayNameResource(weekday: DayOfWeek): StringResource = when (weekday) {
    DayOfWeek.MONDAY -> Res.string.ui_schedule_monday
    DayOfWeek.TUESDAY -> Res.string.ui_schedule_tuesday
    DayOfWeek.WEDNESDAY -> Res.string.ui_schedule_wednesday
    DayOfWeek.THURSDAY -> Res.string.ui_schedule_thursday
    DayOfWeek.FRIDAY -> Res.string.ui_schedule_friday
    DayOfWeek.SATURDAY -> Res.string.ui_schedule_saturday
    DayOfWeek.SUNDAY -> Res.string.ui_schedule_sunday
}

fun monthNameResource(month: Int): StringResource = when (month) {
    1 -> Res.string.ui_schedule_january
    2 -> Res.string.ui_schedule_february
    3 -> Res.string.ui_schedule_march
    4 -> Res.string.ui_schedule_april
    5 -> Res.string.ui_schedule_may
    6 -> Res.string.ui_schedule_june
    7 -> Res.string.ui_schedule_july
    8 -> Res.string.ui_schedule_august
    9 -> Res.string.ui_schedule_september
    10 -> Res.string.ui_schedule_october
    11 -> Res.string.ui_schedule_november
    else -> Res.string.ui_schedule_december
}

fun formatStandingTime(time: LocalTime, am: String, pm: String): String =
    "${((time.hour + 11) % 12) + 1}:${time.minute.toString().padStart(2, '0')} ${if (time.hour < 12) am else pm}"

@Composable
fun standingTimeLabel(time: LocalTime): String = formatStandingTime(
    time, stringResource(Res.string.ui_schedule_am), stringResource(Res.string.ui_schedule_pm),
)

@Composable
fun standingScheduleLabel(weekday: DayOfWeek, time: LocalTime): String = stringResource(
    Res.string.standing_table_schedule_label, stringResource(weekdayNameResource(weekday)), standingTimeLabel(time),
)

@Composable
fun standingTableNextLabel(nextStartsAt: Instant, timeZone: TimeZone, now: Instant): String {
    val next = nextStartsAt.toLocalDateTime(timeZone).date
    val today = now.toLocalDateTime(timeZone).date
    return when (next) {
        today -> stringResource(Res.string.standing_table_home_tonight)
        today.plus(1, DateTimeUnit.DAY) -> stringResource(Res.string.standing_table_home_tomorrow)
        else -> stringResource(Res.string.standing_table_home_date, stringResource(weekdayNameResource(next.dayOfWeek)).take(3), stringResource(monthNameResource(next.month.ordinal + 1)), next.day)
    }
}

