package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ENG-38: install/device facts must reach Grafana as **resource** attributes,
 * so every record a session produces is filterable on them without the event
 * having to restate them.
 */
class InstallFactsResourceTest {

    private val processor = RecordingLogRecordProcessor()

    private fun plantTree(facts: InstallFacts) {
        KLog.plant(
            GrafanaLogTree(
                exportEnabled = { true },
                sampleRate = { 1.0 },
                klogForwardingEnabled = { false },
                currentSessionId = { "session-uuid-1" },
                currentInstallId = { "install-uuid-1" },
                isOffline = { false },
                installFacts = { facts },
                processorFactory = { processor },
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        KLog.clearTrees()
    }

    @Test
    fun retailInstall_stampsEveryFactOnTheResource() {
        plantTree(RetailInstallFacts)

        KLog.logEvent("hand.completed")

        val resource = processor.records.single().resourceAttributes
        assertEquals(true, resource["genuine_install"])
        assertEquals(false, resource["is_emulator"])
        assertEquals(false, resource["is_sideloaded"])
        assertEquals(false, resource["is_rooted"])
        assertEquals("play_store", resource["installer_package"])
        assertEquals("high", resource["device_class"])
        assertEquals("15", resource["os_version"])
    }

    @Test
    fun factsRideOnTheResourceNotTheRecord() {
        plantTree(RetailInstallFacts)

        KLog.logEvent("hand.completed")

        val record = processor.records.single()
        assertTrue(NOISE_KEYS.none { it in record.attributes })
    }

    @Test
    fun emulatorInstall_isTaggedNotDropped() {
        plantTree(RetailInstallFacts.copy(isEmulator = true))

        KLog.logEvent("hand.completed")

        val record = processor.records.single()
        assertEquals("hand.completed", record.eventName)
        assertEquals(true, record.resourceAttributes["is_emulator"])
        assertEquals(false, record.resourceAttributes["genuine_install"])
    }

    @Test
    fun sideloadedInstall_reportsSourceAndFailsTheGenuineCheck() {
        plantTree(RetailInstallFacts.copy(source = InstallSource.None))

        KLog.logEvent("hand.completed")

        val resource = processor.records.single().resourceAttributes
        assertEquals("none", resource["installer_package"])
        assertEquals(true, resource["is_sideloaded"])
        assertEquals(false, resource["genuine_install"])
    }

    @Test
    fun unresolvedFacts_stillCountAsAGenuineInstall() {
        plantTree(InstallFacts.Unresolved)

        KLog.logEvent("hand.completed")

        val resource = processor.records.single().resourceAttributes
        assertEquals("unknown", resource["installer_package"])
        assertEquals(false, resource["is_sideloaded"])
        assertEquals(true, resource["genuine_install"])
    }

    @Test
    fun existingResourceAttributesSurvive() {
        plantTree(RetailInstallFacts)

        KLog.logEvent("hand.completed")

        val resource = processor.records.single().resourceAttributes
        assertTrue("platform" in resource)
        assertTrue("release_channel" in resource)
        assertTrue("deployment.environment" in resource)
    }

    private companion object {
        val NOISE_KEYS = listOf(
            "genuine_install",
            "is_emulator",
            "is_sideloaded",
            "is_rooted",
            "installer_package",
            "device_class",
            "os_version",
        )
    }
}
