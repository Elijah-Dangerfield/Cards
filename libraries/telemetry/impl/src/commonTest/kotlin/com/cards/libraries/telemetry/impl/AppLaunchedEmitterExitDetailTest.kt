package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.core.logging.KLog
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wiring check for `app.exit_detail`: [AppLaunchedEmitter] must ask the
 * platform for [PreviousExitDetail] on the same cold-boot foreground that
 * emits `app.launched`, and only emit the sibling event when there's
 * something to say — most providers (iOS, and Android below API 30) return
 * null, and a launch that carries no detail should carry no event either.
 */
class AppLaunchedEmitterExitDetailTest {

    private val processor = RecordingLogRecordProcessor()

    private fun buildEmitter(provider: PreviousExitProvider): AppLaunchedEmitter {
        KLog.plant(
            GrafanaLogTree(
                exportEnabled = { true },
                sampleRate = { 1.0 },
                klogForwardingEnabled = { false },
                currentSessionId = { "session-uuid-1" },
                currentInstallId = { "install-uuid-1" },
                isOffline = { false },
                installFacts = { RetailInstallFacts },
                processorFactory = { processor },
            ),
        )
        return AppLaunchedEmitter(previousExitProvider = provider)
    }

    @AfterTest
    fun tearDown() {
        KLog.clearTrees()
    }

    @Test
    fun detailPresent_emitsSiblingEventAlongsideLaunch() {
        val emitter = buildEmitter(
            FakePreviousExitProvider(
                exit = PreviousExit.Oom,
                detail = PreviousExitDetail(importance = 400, pssKb = 111_222L, rssKb = 222_333L, description = null),
            ),
        )

        emitter.onForeground(AppEvent.OnForeground(isColdBoot = true))

        assertEquals(
            listOf("app.launched", "app.exit_detail"),
            processor.records.map { it.eventName },
        )
        val detailRecord = processor.records.single { it.eventName == "app.exit_detail" }
        assertEquals(400, detailRecord.attributes["previous_exit_importance"])
        assertEquals(111_222L, detailRecord.attributes["previous_exit_pss_kb"])
    }

    @Test
    fun detailAbsent_omitsSiblingEventEntirely() {
        val emitter = buildEmitter(FakePreviousExitProvider(exit = PreviousExit.Unknown, detail = null))

        emitter.onForeground(AppEvent.OnForeground(isColdBoot = true))

        assertEquals(listOf("app.launched"), processor.records.map { it.eventName })
        assertTrue(processor.records.none { it.eventName == "app.exit_detail" })
    }

    @Test
    fun warmForeground_neverEmitsDetailEvent() {
        val emitter = buildEmitter(
            FakePreviousExitProvider(
                exit = PreviousExit.Oom,
                detail = PreviousExitDetail(importance = 100, pssKb = 1L, rssKb = 1L, description = null),
            ),
        )

        emitter.onForeground(AppEvent.OnForeground(isColdBoot = false))

        assertTrue(processor.records.isEmpty())
    }

    private class FakePreviousExitProvider(
        private val exit: PreviousExit,
        private val detail: PreviousExitDetail?,
    ) : PreviousExitProvider {
        override fun previousExit(): PreviousExit = exit
        override fun previousExitDetail(): PreviousExitDetail? = detail
    }
}
