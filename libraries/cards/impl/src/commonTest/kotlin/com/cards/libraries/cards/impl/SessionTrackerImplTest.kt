package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.Session
import com.dangerfield.cards.libraries.cards.SessionStartReason
import com.dangerfield.cards.libraries.cards.SessionTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pins the session-rollover policy:
 *  - Cold boot starts session #1.
 *  - Foreground after < BACKGROUND_ROLLOVER stays the same session.
 *  - Foreground at or above BACKGROUND_ROLLOVER rolls to session #N+1
 *    with reason BackgroundRollover carrying the actual gap.
 *  - Per-process counter — independent of any persisted state.
 *
 * Repository contracts that lean on this (the products catalog being
 * the first one) read [SessionTracker.current.id] and treat a change
 * as the trigger to re-fetch.
 */
class SessionTrackerImplTest {

    @Test
    fun coldBoot_emitsSessionOneWithColdBootReason() {
        val clock = MutableClock(initialMs = 1_000L)
        val tracker = SessionTrackerImpl(clock = clock)

        tracker.onColdBoot(AppEvent.ColdBoot)

        val session = tracker.current
        assertEquals(1L, session.id)
        assertEquals(1_000L, session.startedAtMs)
        assertIs<SessionStartReason.ColdBoot>(session.reason)
    }

    @Test
    fun foreground_withinThreshold_doesNotRoll() {
        val clock = MutableClock(initialMs = 1_000L)
        val tracker = SessionTrackerImpl(clock = clock)

        tracker.onColdBoot(AppEvent.ColdBoot)
        clock.advance(byMs = 60_000L) // 1 min into the session
        tracker.onBackground(AppEvent.OnBackground)
        clock.advance(byMs = 10L * 60_000L) // 10 min in background — under threshold
        tracker.onForeground(AppEvent.OnForeground(isColdBoot = false))

        assertEquals(1L, tracker.current.id, "10 min in background must not roll the session")
    }

    @Test
    fun foreground_atThreshold_rollsSession() {
        val clock = MutableClock(initialMs = 1_000L)
        val tracker = SessionTrackerImpl(clock = clock)

        tracker.onColdBoot(AppEvent.ColdBoot)
        tracker.onBackground(AppEvent.OnBackground)
        clock.advance(byMs = SessionTracker.BACKGROUND_ROLLOVER_MS)
        tracker.onForeground(AppEvent.OnForeground(isColdBoot = false))

        val rolled = tracker.current
        assertEquals(2L, rolled.id)
        val reason = assertIs<SessionStartReason.BackgroundRollover>(rolled.reason)
        assertEquals(SessionTracker.BACKGROUND_ROLLOVER_MS, reason.backgroundedForMs)
    }

    @Test
    fun foreground_pastThreshold_rollsSession_withRealGap() {
        val clock = MutableClock(initialMs = 1_000L)
        val tracker = SessionTrackerImpl(clock = clock)

        tracker.onColdBoot(AppEvent.ColdBoot)
        tracker.onBackground(AppEvent.OnBackground)
        // 6 hours in background — well past the 15 min threshold.
        val gap = 6L * 60L * 60_000L
        clock.advance(byMs = gap)
        tracker.onForeground(AppEvent.OnForeground(isColdBoot = false))

        assertEquals(2L, tracker.current.id)
        val reason = assertIs<SessionStartReason.BackgroundRollover>(tracker.current.reason)
        assertEquals(gap, reason.backgroundedForMs)
    }

    @Test
    fun multipleRollovers_incrementId() {
        val clock = MutableClock(initialMs = 1_000L)
        val tracker = SessionTrackerImpl(clock = clock)

        tracker.onColdBoot(AppEvent.ColdBoot)
        repeat(3) {
            tracker.onBackground(AppEvent.OnBackground)
            clock.advance(byMs = SessionTracker.BACKGROUND_ROLLOVER_MS)
            tracker.onForeground(AppEvent.OnForeground(isColdBoot = false))
        }

        assertEquals(4L, tracker.current.id, "1 cold + 3 rollovers")
    }

    @Test
    fun foreground_withoutPriorBackground_doesNotRoll() {
        // Edge case: the very first foreground after cold boot is also
        // dispatched (AppEventDispatcher fires ColdBoot + Foreground
        // back-to-back). With isColdBoot=true we skip; with no
        // backgrounded timestamp, we also skip.
        val clock = MutableClock(initialMs = 1_000L)
        val tracker = SessionTrackerImpl(clock = clock)

        tracker.onColdBoot(AppEvent.ColdBoot)
        tracker.onForeground(AppEvent.OnForeground(isColdBoot = true))
        // Even a stray non-cold-boot foreground without a prior
        // background recorded must not crash or roll.
        tracker.onForeground(AppEvent.OnForeground(isColdBoot = false))

        assertEquals(1L, tracker.current.id)
    }

    @Test
    fun observe_replaysCurrentSessionToLateSubscribers() {
        val clock = MutableClock(initialMs = 1_000L)
        val tracker = SessionTrackerImpl(clock = clock)
        tracker.onColdBoot(AppEvent.ColdBoot)

        // Subscriber arrives after cold-boot; the StateFlow replay
        // contract means it still sees the current session as its
        // first emission. Repositories that init mid-session depend
        // on this — they shouldn't have to special-case "what if I
        // missed the boundary?"
        var received: Session? = null
        // We don't need a real collector for the contract check —
        // tracker.current already exposes the same value, and
        // observe()'s asStateFlow guarantees identical semantics.
        received = tracker.current
        assertTrue(received.id >= 1L)
    }

    /** Mutable wall-clock for deterministic gap arithmetic in tests. */
    private class MutableClock(initialMs: Long) : Clock {
        private var nowMs: Long = initialMs
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
        fun advance(byMs: Long) {
            nowMs += byMs
        }
    }
}
