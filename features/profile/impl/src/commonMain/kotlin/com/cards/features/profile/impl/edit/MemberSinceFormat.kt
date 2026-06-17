package com.dangerfield.cards.features.profile.impl.edit

import androidx.compose.runtime.Composable
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.month_april
import cards.libraries.resources.generated.resources.month_august
import cards.libraries.resources.generated.resources.month_december
import cards.libraries.resources.generated.resources.month_february
import cards.libraries.resources.generated.resources.month_january
import cards.libraries.resources.generated.resources.month_july
import cards.libraries.resources.generated.resources.month_june
import cards.libraries.resources.generated.resources.month_march
import cards.libraries.resources.generated.resources.month_may
import cards.libraries.resources.generated.resources.month_november
import cards.libraries.resources.generated.resources.month_october
import cards.libraries.resources.generated.resources.month_september
import cards.libraries.resources.generated.resources.month_unknown
import cards.libraries.resources.generated.resources.profile_card_member_since_value
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun monthYearLabel(instant: Instant): String {
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return stringResource(
        Res.string.profile_card_member_since_value,
        stringResource(monthResource(local.monthNumber)),
        local.year,
    )
}

private fun monthResource(monthNumber: Int): StringResource = when (monthNumber) {
    1 -> Res.string.month_january
    2 -> Res.string.month_february
    3 -> Res.string.month_march
    4 -> Res.string.month_april
    5 -> Res.string.month_may
    6 -> Res.string.month_june
    7 -> Res.string.month_july
    8 -> Res.string.month_august
    9 -> Res.string.month_september
    10 -> Res.string.month_october
    11 -> Res.string.month_november
    12 -> Res.string.month_december
    else -> Res.string.month_unknown
}
