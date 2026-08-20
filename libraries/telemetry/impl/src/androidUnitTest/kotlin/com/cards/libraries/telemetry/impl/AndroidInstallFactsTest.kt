package com.dangerfield.cards.libraries.telemetry.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidInstallFactsTest {

    @Test
    fun playInstallIsTheOnlyOneThatReadsAsPlay() {
        assertEquals(InstallSource.PlayStore, installSourceForPackage("com.android.vending"))
        assertEquals(InstallSource.AmazonAppstore, installSourceForPackage("com.amazon.venezia"))
        assertEquals(InstallSource.GalaxyStore, installSourceForPackage("com.sec.android.app.samsungapps"))
    }

    @Test
    fun adbInstallReportsNoInstallerAtAll() {
        assertEquals(InstallSource.None, installSourceForPackage(null))
        assertEquals(InstallSource.None, installSourceForPackage(""))
    }

    @Test
    fun anUnrecognisedInstallerFoldsIntoOtherRatherThanLeakingCardinality() {
        assertEquals(InstallSource.Other, installSourceForPackage("com.google.android.packageinstaller"))
        assertEquals(InstallSource.Other, installSourceForPackage("com.some.thirdparty.store"))
        assertTrue(installSourceForPackage("com.some.thirdparty.store").isSideload)
    }

    @Test
    fun knownEmulatorImagesAreDetected() {
        assertTrue(
            isEmulatorBuild(
                fingerprint = "google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036/11228894:user/release-keys",
                model = "sdk_gphone64_arm64",
                manufacturer = "Google",
                brand = "google",
                device = "emu64a",
                product = "sdk_gphone64_arm64",
                hardware = "ranchu",
            ),
        )
        assertTrue(
            isEmulatorBuild(
                fingerprint = "generic/sdk/generic:8.0.0/OPR6.170623.017/4479522:userdebug/test-keys",
                model = "Android SDK built for x86",
                manufacturer = "unknown",
                brand = "generic",
                device = "generic",
                product = "sdk",
                hardware = "goldfish",
            ),
        )
        assertTrue(
            isEmulatorBuild(
                fingerprint = "generic/vbox86p/vbox86p:9/PI/genymotion:userdebug/test-keys",
                model = "Custom Phone",
                manufacturer = "Genymotion",
                brand = "generic",
                device = "vbox86p",
                product = "vbox86p",
                hardware = "vbox86",
            ),
        )
    }

    @Test
    fun retailHandsetsAreNotMistakenForEmulators() {
        assertFalse(
            isEmulatorBuild(
                fingerprint = "google/husky/husky:14/AP1A.240505.005/11583682:user/release-keys",
                model = "Pixel 8 Pro",
                manufacturer = "Google",
                brand = "google",
                device = "husky",
                product = "husky",
                hardware = "husky",
            ),
        )
        assertFalse(
            isEmulatorBuild(
                fingerprint = "samsung/e1qxxx/e1q:14/UP1A.231005.007/S911BXXU4CXD1:user/release-keys",
                model = "SM-S911B",
                manufacturer = "samsung",
                brand = "samsung",
                device = "e1q",
                product = "e1qxxx",
                hardware = "qcom",
            ),
        )
    }
}
