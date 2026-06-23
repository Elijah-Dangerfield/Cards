package com.dangerfield.cards.features.room.impl.ui

import com.dangerfield.cards.features.room.impl.SeatView

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
import cards.libraries.resources.generated.resources.room_seat_tenure_hands_multi
import cards.libraries.resources.generated.resources.room_seat_tenure_hands_single
import cards.libraries.resources.generated.resources.room_seat_tenure_playing_since
import cards.libraries.resources.generated.resources.room_seat_tenure_playing_since_supporting
import cards.libraries.resources.generated.resources.room_seat_tenure_supporting_human
import cards.libraries.resources.generated.resources.room_seat_tenure_supporting_other
import com.dangerfield.cards.libraries.ui.components.ListItemAccessory
import com.dangerfield.cards.libraries.ui.components.ListSectionItem
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
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
 *
 * Authoring split mirrors the [BotPlayingStyle] / [BotDifficultyTier] pattern
 * — [tenureSpecs] is a pure, testable function returning a sealed shape that
 * holds [StringResource] references; the [Composable] [tenureRows] resolves
 * those into the [ListSectionItem]s the sheet renders.
 */
@Composable
internal fun tenureRows(seat: SeatView): List<ListSectionItem> = tenureSpecs(seat).map { spec ->
    ListSectionItem(
        headlineText = spec.headline.resolve(),
        supportingText = stringResource(spec.supporting),
        accessory = ListItemAccessory.None,
    )
}

internal fun tenureSpecs(seat: SeatView): List<TenureSpec> = buildList {
    if (seat.handsAtTable > 0) {
        add(
            TenureSpec(
                headline = if (seat.handsAtTable == 1) {
                    TenureHeadline.HandsSingle
                } else {
                    TenureHeadline.HandsMulti(seat.handsAtTable)
                },
                supporting = if (seat.isHuman) {
                    Res.string.room_seat_tenure_supporting_human
                } else {
                    Res.string.room_seat_tenure_supporting_other
                },
            ),
        )
    }
    seat.playingSinceEpochMs?.let { epochMs ->
        val components = playingSinceComponents(epochMs)
        add(
            TenureSpec(
                headline = TenureHeadline.PlayingSince(
                    monthResource = components.monthResource,
                    year = components.year,
                ),
                supporting = Res.string.room_seat_tenure_playing_since_supporting,
            ),
        )
    }
}

internal data class TenureSpec(
    val headline: TenureHeadline,
    val supporting: StringResource,
)

internal sealed class TenureHeadline {
    data object HandsSingle : TenureHeadline()
    data class HandsMulti(val count: Int) : TenureHeadline()
    data class PlayingSince(val monthResource: StringResource, val year: Int) : TenureHeadline()
}

@Composable
private fun TenureHeadline.resolve(): String = when (this) {
    TenureHeadline.HandsSingle -> stringResource(Res.string.room_seat_tenure_hands_single)
    is TenureHeadline.HandsMulti -> stringResource(Res.string.room_seat_tenure_hands_multi, count)
    is TenureHeadline.PlayingSince -> stringResource(
        Res.string.room_seat_tenure_playing_since,
        stringResource(monthResource),
        year,
    )
}

/**
 * Friendly month + year for the "Playing since" row. Year-only would
 * read as too coarse ("Playing since 2026" — when did *this year*?);
 * full date would read as too precise. `Month Year` lands in the
 * middle and matches how players talk about tenure.
 */
internal fun playingSinceComponents(epochMs: Long): PlayingSinceComponents {
    val instant = Instant.fromEpochMilliseconds(epochMs)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return PlayingSinceComponents(
        monthResource = monthResource(local.monthNumber),
        year = local.year,
    )
}

internal data class PlayingSinceComponents(
    val monthResource: StringResource,
    val year: Int,
)

internal fun monthResource(monthNumber: Int): StringResource = when (monthNumber) {
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
