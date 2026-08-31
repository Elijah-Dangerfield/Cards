package com.dangerfield.cards.libraries.telemetry.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallFactsTest {

    @Test
    fun anyOneNoiseSignal_disqualifiesTheInstall() {
        assertTrue(RetailInstallFacts.isGenuineInstall)
        assertFalse(RetailInstallFacts.copy(isEmulator = true).isGenuineInstall)
        assertFalse(RetailInstallFacts.copy(isRooted = true).isGenuineInstall)
        assertFalse(RetailInstallFacts.copy(source = InstallSource.Other).isGenuineInstall)
    }

    @Test
    fun testFlightIsNotASideload() {
        val testFlight = RetailInstallFacts.copy(source = InstallSource.TestFlight)

        assertFalse(testFlight.isSideloaded)
        assertTrue(testFlight.isGenuineInstall)
    }

    @Test
    fun aFailedLookupNeverManufacturesASideload() {
        assertFalse(InstallSource.Unknown.isSideload)
        assertTrue(InstallFacts.Unresolved.isGenuineInstall)
    }

    @Test
    fun attributeValuesAreStableWireTokens() {
        assertEquals(
            listOf(
                "play_store",
                "amazon_appstore",
                "galaxy_store",
                "app_store",
                "testflight",
                "other",
                "none",
                "unknown",
            ),
            InstallSource.entries.map { it.value },
        )
        assertEquals(listOf("low", "medium", "high", "unknown"), DeviceClass.entries.map { it.value })
    }

    @Test
    fun deviceClass_needsBothMemoryAndCoresToPromote() {
        assertEquals(DeviceClass.High, deviceClassFor(gib(8), 8))
        assertEquals(DeviceClass.Medium, deviceClassFor(gib(8), 4))
        assertEquals(DeviceClass.Medium, deviceClassFor(gib(4), 8))
        assertEquals(DeviceClass.Low, deviceClassFor(gib(2), 8))
        assertEquals(DeviceClass.Low, deviceClassFor(gib(8), 2))
    }

    @Test
    fun deviceClass_isUnknownWhenTheLookupReturnedNothing() {
        assertEquals(DeviceClass.Unknown, deviceClassFor(0L, 8))
        assertEquals(DeviceClass.Unknown, deviceClassFor(gib(8), 0))
    }

    private fun gib(count: Long): Long = count * 1024L * 1024L * 1024L
}
