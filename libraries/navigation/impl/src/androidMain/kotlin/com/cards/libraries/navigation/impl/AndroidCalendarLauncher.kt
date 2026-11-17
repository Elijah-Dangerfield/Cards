package com.dangerfield.cards.libraries.navigation.impl

import android.content.Intent
import android.provider.CalendarContract
import com.dangerfield.cards.libraries.cards.ActivityProvider
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.navigation.CalendarLauncher
import com.dangerfield.cards.libraries.navigation.CalendarResult
import com.dangerfield.cards.libraries.navigation.RecurringCalendarEvent
import com.dangerfield.cards.libraries.navigation.WEEKLY_RECURRENCE_RULE
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AndroidCalendarLauncher(
    private val activityProvider: ActivityProvider,
) : CalendarLauncher {
    override suspend fun addWeekly(event: RecurringCalendarEvent): CalendarResult {
        val activity = activityProvider.currentActivity() ?: return CalendarResult.Unavailable
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, event.title)
            putExtra(CalendarContract.Events.DESCRIPTION, event.descriptionWithUrl)
            putExtra(CalendarContract.Events.EVENT_LOCATION, event.url)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.startEpochMilliseconds)
            putExtra(
                CalendarContract.EXTRA_EVENT_END_TIME,
                event.startEpochMilliseconds + event.durationMinutes * 60_000L,
            )
            putExtra(CalendarContract.Events.EVENT_TIMEZONE, event.timeZoneId)
            putExtra(CalendarContract.Events.RRULE, WEEKLY_RECURRENCE_RULE)
        }
        if (intent.resolveActivity(activity.packageManager) == null) return CalendarResult.Unavailable
        // ACTION_INSERT reports only that the editor opened, never whether the user saved.
        return Catching {
            activity.startActivity(intent)
            CalendarResult.Added
        }.logOnFailure("Failed to open calendar editor").getOrDefault(CalendarResult.Unavailable)
    }
}
