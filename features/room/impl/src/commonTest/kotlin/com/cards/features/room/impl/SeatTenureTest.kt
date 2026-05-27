package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.gameplay.HandParticipation
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeatTenureTest {

    @Test
    fun handsAtTable_singular() {
        assertEquals("1 hand at this table", formatHandsAtTable(1))
    }

    @Test
    fun handsAtTable_plural() {
        assertEquals("12 hands at this table", formatHandsAtTable(12))
    }

    @Test
    fun playingSince_rendersMonthAndYear_inDeviceTimeZone() {
        // 2025-03-15 00:30 UTC — depending on the device TZ this may roll
        // back a day, but the month + year stay the same on every IANA TZ
        // (the date crosses no month boundary in any zone). Asserting the
        // month and year only keeps this test stable across CI hosts.
        val epochMs = LocalDateTime(2025, 3, 15, 0, 30, 0)
            .toInstant(TimeZone.UTC)
            .toEpochMilliseconds()
        val formatted = formatPlayingSince(epochMs)
        assertTrue(formatted.contains("March"), "expected March, got $formatted")
        assertTrue(formatted.contains("2025"), "expected 2025, got $formatted")
    }

    @Test
    fun tenureRows_humanSeat_includesBothRows() {
        val epochMs = LocalDateTime(2025, 7, 4, 12, 0)
            .toInstant(TimeZone.UTC)
            .toEpochMilliseconds()
        val seat = sampleSeat(
            isHuman = true,
            handsAtTable = 23,
            playingSinceEpochMs = epochMs,
        )
        val rows = tenureRows(seat)
        assertEquals(2, rows.size)
        assertEquals("23 hands at this table", rows[0].headlineText)
        assertTrue(
            rows[1].headlineText.startsWith("Playing since"),
            "second row should be the join-date line, got ${rows[1].headlineText}",
        )
    }

    @Test
    fun tenureRows_botSeat_omitsPlayingSince() {
        val seat = sampleSeat(
            isHuman = false,
            handsAtTable = 5,
            playingSinceEpochMs = null,
        )
        val rows = tenureRows(seat)
        assertEquals(1, rows.size, "bots have no playing-since source — only the hand count row renders")
        assertEquals("5 hands at this table", rows[0].headlineText)
    }

    @Test
    fun tenureRows_emptyOrPreGame_yieldsNoRows() {
        val seat = sampleSeat(handsAtTable = 0, playingSinceEpochMs = null)
        assertTrue(
            tenureRows(seat).isEmpty(),
            "zero hands and no tenure source = section is hidden by caller",
        )
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
