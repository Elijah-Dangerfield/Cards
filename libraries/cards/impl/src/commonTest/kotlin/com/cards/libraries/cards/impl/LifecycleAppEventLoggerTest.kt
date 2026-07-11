package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.core.logging.EXTRA_APP_EVENT
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.LogEntry
import com.dangerfield.cards.libraries.core.logging.LogId
import com.dangerfield.cards.libraries.core.logging.LogTree
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class LifecycleAppEventLoggerTest {

    private val tree = RecordingTree()
    private val timeSource = TestTimeSource()
    private val logger = LifecycleAppEventLogger(timeSource)

    private fun plantTree() = KLog.plant(tree)

    @AfterTest
    fun tearDown() {
        KLog.clearTrees()
    }

    @Test
    fun backgrounded_carriesWholeSecondsSinceForeground() {
        plantTree()

        logger.onForeground(AppEvent.OnForeground(isColdBoot = true))
        timeSource += 95.seconds + 400.milliseconds
        logger.onBackground(AppEvent.OnBackground)

        val entry = tree.eventEntries("app.backgrounded").single()
        assertEquals(95L, entry.context.extras["session_duration_sec"])
    }

    @Test
    fun eachForeground_restartsTheClock() {
        plantTree()

        logger.onForeground(AppEvent.OnForeground(isColdBoot = true))
        timeSource += 10.seconds
        logger.onBackground(AppEvent.OnBackground)
        timeSource += 300.seconds
        logger.onForeground(AppEvent.OnForeground(isColdBoot = false))
        timeSource += 5.seconds
        logger.onBackground(AppEvent.OnBackground)

        val durations = tree.eventEntries("app.backgrounded").map { it.context.extras["session_duration_sec"] }
        assertEquals(listOf<Any?>(10L, 5L), durations)
    }

    @Test
    fun backgroundWithoutForeground_omitsTheAttribute() {
        plantTree()

        logger.onBackground(AppEvent.OnBackground)

        val entry = tree.eventEntries("app.backgrounded").single()
        assertFalse("session_duration_sec" in entry.context.extras)
    }

    private class RecordingTree : LogTree() {
        val entries = mutableListOf<LogEntry>()

        override fun log(entry: LogEntry): LogId? {
            entries += entry
            return null
        }

        fun eventEntries(eventName: String): List<LogEntry> =
            entries.filter { it.context.extras[EXTRA_APP_EVENT] == eventName }
    }
}
