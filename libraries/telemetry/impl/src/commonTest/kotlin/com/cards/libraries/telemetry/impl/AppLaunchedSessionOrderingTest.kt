package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.core.logging.KLog
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ENG-24: `app.launched` used to fire from [GrafanaAppEvents]' init, which
 * runs at DI resolution — before the first foreground makes the session
 * tracker roll session #1. Every cold start's launch event landed with the
 * tracker's pre-boot sentinel uuid, orphaned from the rest of its session
 * in every session-keyed funnel. [AppLaunchedEmitter] waits for the
 * cold-boot foreground, by which point the session id is settled.
 */
class AppLaunchedSessionOrderingTest {

    private val processor = RecordingLogRecordProcessor()
    private var currentSession = "sentinel-uuid"

    @AfterTest
    fun tearDown() {
        KLog.clearTrees()
    }

    private fun buildEmitter(): AppLaunchedEmitter {
        KLog.plant(
            GrafanaLogTree(
                exportEnabled = { true },
                sampleRate = { 1.0 },
                klogForwardingEnabled = { false },
                currentSessionId = { currentSession },
                currentInstallId = { "install-uuid-1" },
                isOffline = { false },
                processorFactory = { processor },
            ),
        )
        return AppLaunchedEmitter(
            previousExitProvider = object : PreviousExitProvider {
                override fun previousExit(): PreviousExit = PreviousExit.Unknown
            },
        )
    }

    @Test
    fun appLaunched_waitsForColdBootForeground_soSessionIdIsSettled() {
        val emitter = buildEmitter()
        assertTrue(
            processor.records.none { it.eventName == "app.launched" },
            "construction must not emit app.launched — the session hasn't rolled yet",
        )

        currentSession = "settled-uuid"
        emitter.onForeground(AppEvent.OnForeground(isColdBoot = true))

        val record = processor.records.single { it.eventName == "app.launched" }
        assertEquals("settled-uuid", record.attributes["session_id"])
        assertEquals("unknown", record.attributes["previous_exit"])
    }

    @Test
    fun warmForeground_doesNotReEmitAppLaunched() {
        val emitter = buildEmitter()

        emitter.onForeground(AppEvent.OnForeground(isColdBoot = false))

        assertTrue(processor.records.none { it.eventName == "app.launched" })
    }
}
