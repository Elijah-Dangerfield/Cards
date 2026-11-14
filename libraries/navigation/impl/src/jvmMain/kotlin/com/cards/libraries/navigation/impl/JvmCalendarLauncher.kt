package com.dangerfield.cards.libraries.navigation.impl

import com.dangerfield.cards.libraries.navigation.CalendarLauncher
import com.dangerfield.cards.libraries.navigation.CalendarResult
import com.dangerfield.cards.libraries.navigation.RecurringCalendarEvent
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class JvmCalendarLauncher : CalendarLauncher {
    override suspend fun addWeekly(event: RecurringCalendarEvent): CalendarResult = CalendarResult.Unavailable
}
