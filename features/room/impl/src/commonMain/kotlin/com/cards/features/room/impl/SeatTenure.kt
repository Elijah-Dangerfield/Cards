package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.ui.components.ListItemAccessory
import com.dangerfield.cards.libraries.ui.components.ListSectionItem
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Builds the "At this table" section rows for the tap-an-opponent profile
 * sheet — "{N} hands at this table" for every populated seat, plus
 * "Playing since {Month Year}" for the local human seat (the only seat
 * with a [com.dangerfield.cards.libraries.identity.profile.Profile.Authenticated]
 * source today). Empty list = section is hidden.
 *
 * Bot tenure ("Playing since") deliberately stays absent: a bot has no
 * meaningful created-at, and inventing one (session-start) feels off
 * once the user has played enough to know bots are persistent rosters
 * across sessions. Remote-human tenure waits for the MP profile-of-a-
 * stranger endpoint to land.
 */
internal fun tenureRows(seat: SeatView): List<ListSectionItem> = buildList {
    if (seat.handsAtTable > 0) {
        add(
            ListSectionItem(
                headlineText = formatHandsAtTable(seat.handsAtTable),
                supportingText = if (seat.isHuman) {
                    "Hands you've shared with this table."
                } else {
                    "Hands they've shared with this table."
                },
                accessory = ListItemAccessory.None,
            ),
        )
    }
    seat.playingSinceEpochMs?.let { epochMs ->
        add(
            ListSectionItem(
                headlineText = "Playing since ${formatPlayingSince(epochMs)}",
                supportingText = "When your account joined.",
                accessory = ListItemAccessory.None,
            ),
        )
    }
}

internal fun formatHandsAtTable(count: Int): String =
    if (count == 1) "1 hand at this table" else "$count hands at this table"

/**
 * Friendly month + year for the "Playing since" row. Year-only would
 * read as too coarse ("Playing since 2026" — when did *this year*?);
 * full date would read as too precise. `Month Year` lands in the
 * middle and matches how players talk about tenure.
 */
internal fun formatPlayingSince(epochMs: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMs)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${monthName(local.monthNumber)} ${local.year}"
}

private fun monthName(monthNumber: Int): String = when (monthNumber) {
    1 -> "January"
    2 -> "February"
    3 -> "March"
    4 -> "April"
    5 -> "May"
    6 -> "June"
    7 -> "July"
    8 -> "August"
    9 -> "September"
    10 -> "October"
    11 -> "November"
    12 -> "December"
    else -> "—"
}
