package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Catching
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import me.tatarka.inject.annotations.Inject
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosInstallFactsProvider : InstallFactsProvider {

    private val facts: InstallFacts by lazy {
        Catching {
            val simulator = isSimulator()
            InstallFacts(
                source = installSource(),
                isEmulator = simulator,
                isRooted = !simulator && isJailbroken(),
                deviceClass = deviceClassFor(physicalMemoryBytes(), processorCount()),
                osVersion = osVersion(),
            )
        }.getOrDefault(InstallFacts.Unresolved)
    }

    override fun facts(): InstallFacts = facts

    private fun isSimulator(): Boolean =
        NSProcessInfo.processInfo.environment[SIMULATOR_ENV_KEY] != null

    /**
     * iOS records no installing package, so the receipt Apple stapled to the
     * bundle is the distribution channel: `receipt` for the App Store,
     * `sandboxReceipt` for TestFlight. A debug binary also carries a sandbox
     * receipt, which is why [BuildInfo.isTestFlight] gates on `!isDebug`.
     *
     * Anything else reports [InstallSource.Unknown] rather than
     * [InstallSource.None]. A developer-signed sideload lands there, but so
     * does a genuine App Store install whose `appStoreReceiptURL` we couldn't
     * read — Apple deprecated that API in favour of StoreKit 2, and it can
     * come back nil on a perfectly ordinary retail install. `None` is a
     * sideload verdict and `Unknown` is not, so the difference decides whether
     * the dashboards drop those users. Android maps its failed lookup the same
     * way: under-report noise rather than delete real users.
     */
    private fun installSource(): InstallSource = when {
        BuildInfo.isTestFlight -> InstallSource.TestFlight
        NSBundle.mainBundle.appStoreReceiptURL?.lastPathComponent == APP_STORE_RECEIPT -> InstallSource.AppStore
        else -> InstallSource.Unknown
    }

    /**
     * Skipped on the simulator, where the host is macOS and every one of these
     * paths exists — running it there would report the whole simulator fleet
     * as jailbroken.
     */
    private fun isJailbroken(): Boolean {
        val fileManager = NSFileManager.defaultManager
        return JAILBREAK_PATHS.any { fileManager.fileExistsAtPath(it) }
    }

    private fun physicalMemoryBytes(): Long = NSProcessInfo.processInfo.physicalMemory.toLong()

    private fun processorCount(): Int = NSProcessInfo.processInfo.activeProcessorCount.toInt()

    @OptIn(ExperimentalForeignApi::class)
    private fun osVersion(): String = NSProcessInfo.processInfo.operatingSystemVersion.useContents {
        "$majorVersion.$minorVersion.$patchVersion"
    }

    private companion object {
        const val SIMULATOR_ENV_KEY = "SIMULATOR_DEVICE_NAME"
        const val APP_STORE_RECEIPT = "receipt"

        val JAILBREAK_PATHS = listOf(
            "/Applications/Cydia.app",
            "/Applications/Sileo.app",
            "/Library/MobileSubstrate/MobileSubstrate.dylib",
            "/usr/sbin/sshd",
            "/etc/apt",
            "/private/var/lib/apt",
            "/bin/bash",
        )
    }
}
