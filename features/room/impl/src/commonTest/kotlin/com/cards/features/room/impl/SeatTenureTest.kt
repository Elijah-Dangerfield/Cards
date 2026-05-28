package com.dangerfield.cards.features.room.impl

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
import cards.libraries.resources.generated.resources.room_seat_tenure_playing_since_supporting
import cards.libraries.resources.generated.resources.room_seat_tenure_supporting_human
import cards.libraries.resources.generated.resources.room_seat_tenure_supporting_other
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeatTenureTest {

    @Test
    fun handsAtTable_singular_specHeadlineIsSingleResource() {
        val seat = sampleSeat(isHuman = true, handsAtTable = 1)
        val spec = tenureSpecs(seat).single()
        assertEquals(TenureHeadline.HandsSingle, spec.headline)
        assertEquals(Res.string.room_seat_tenure_supporting_human, spec.supporting)
    }

    @Test
    fun handsAtTable_plural_specCarriesCountInHeadline() {
        val seat = sampleSeat(isHuman = false, handsAtTable = 12)
        val spec = tenureSpecs(seat).single()
        assertEquals(TenureHeadline.HandsMulti(12), spec.headline)
        assertEquals(Res.string.room_seat_tenure_supporting_other, spec.supporting)
    }

    @Test
    fun playingSince_rendersMonthResourceAndYear_inDeviceTimeZone() {
        // 2025-03-15 00:30 UTC — depending on the device TZ this may roll
        // back a day, but the month + year stay the same on every IANA TZ
        // (the date crosses no month boundary in any zone). Asserting the
        // month-resource id and the year keeps this test stable across CI
        // hosts.
        val epochMs = LocalDateTime(2025, 3, 15, 0, 30, 0)
            .toInstant(TimeZone.UTC)
            .toEpochMilliseconds()
        val components = playingSinceComponents(epochMs)
        assertEquals(Res.string.month_march, components.monthResource)
        assertEquals(2025, components.year)
    }

    @Test
    fun tenureSpecs_humanSeat_includesBothSpecs() {
        val epochMs = LocalDateTime(2025, 7, 4, 12, 0)
            .toInstant(TimeZone.UTC)
            .toEpochMilliseconds()
        val seat = sampleSeat(
            isHuman = true,
            handsAtTable = 23,
            playingSinceEpochMs = epochMs,
        )
        val specs = tenureSpecs(seat)
        assertEquals(2, specs.size)
        assertEquals(TenureHeadline.HandsMulti(23), specs[0].headline)
        assertEquals(Res.string.room_seat_tenure_supporting_human, specs[0].supporting)
        assertTrue(
            specs[1].headline is TenureHeadline.PlayingSince,
            "second spec should be the join-date headline, got ${specs[1].headline}",
        )
        assertEquals(Res.string.room_seat_tenure_playing_since_supporting, specs[1].supporting)
    }

    @Test
    fun tenureSpecs_botSeat_omitsPlayingSince() {
        val seat = sampleSeat(
            isHuman = false,
            handsAtTable = 5,
            playingSinceEpochMs = null,
        )
        val specs = tenureSpecs(seat)
        assertEquals(1, specs.size, "bots have no playing-since source — only the hand-count spec emits")
        assertEquals(TenureHeadline.HandsMulti(5), specs[0].headline)
    }

    @Test
    fun tenureSpecs_emptyOrPreGame_yieldsNoSpecs() {
        val seat = sampleSeat(handsAtTable = 0, playingSinceEpochMs = null)
        assertTrue(
            tenureSpecs(seat).isEmpty(),
            "zero hands and no tenure source = section is hidden by caller",
        )
    }

    @Test
    fun monthResource_mapsEachKnownMonth() {
        val expected = listOf(
            1 to Res.string.month_january,
            2 to Res.string.month_february,
            3 to Res.string.month_march,
            4 to Res.string.month_april,
            5 to Res.string.month_may,
            6 to Res.string.month_june,
            7 to Res.string.month_july,
            8 to Res.string.month_august,
            9 to Res.string.month_september,
            10 to Res.string.month_october,
            11 to Res.string.month_november,
            12 to Res.string.month_december,
        )
        for ((monthNumber, resource) in expected) {
            assertEquals(resource, monthResource(monthNumber), "month $monthNumber")
        }
    }

    @Test
    fun monthResource_outOfRange_fallsBackToUnknown() {
        assertEquals(Res.string.month_unknown, monthResource(0))
        assertEquals(Res.string.month_unknown, monthResource(13))
    }

    private fun sampleSeat(
        isHuman: Boolean = false,
        handsAtTable: Int = 0,
        playingSinceEpochMs: Long? = null,
    ): SeatView = SeatView(
        index = if (isHuman) 0 else 1,
        displayName = if (isHuman) "You" else "Bot",
        stack = 1_000,
        contributedThisStreet = 0,
        isActing = false,
        isHuman = isHuman,
        isBot = !isHuman,
        avatarKey = null,
        emoji = null,
        avatarBackgroundColorHex = null,
        holeCards = emptyList(),
        showHoleCardBacks = false,
        participation = HandParticipation.NotDealt,
        seatEmpty = false,
        isBusted = false,
        lastAction = null,
        isDealer = false,
        isSmallBlind = false,
        isBigBlind = false,
        handsAtTable = handsAtTable,
        playingSinceEpochMs = playingSinceEpochMs,
    )
}
