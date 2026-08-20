package com.dangerfield.cards.libraries.telemetry.impl

/**
 * Where an install came from. The value set is closed on purpose — these ride
 * as a resource attribute on every record, so an unbounded raw installer
 * package name would be a cardinality leak for a field whose only job is
 * "retail store or not" (ENG-38).
 *
 * [isSideload] is what `is_sideloaded` reports. An installer we don't
 * recognise counts as one: the recognised set already covers every way a
 * retail user can get the app on either platform, so anything else is a file
 * manager, a third-party store, `adb install`, or a re-signed build.
 * [Unknown] is the exception — a lookup that *failed* is not evidence of a
 * sideload, and guessing there would quietly delete real users from the
 * dashboards this feeds.
 */
enum class InstallSource(val value: String, val isSideload: Boolean) {
    PlayStore("play_store", isSideload = false),
    AmazonAppstore("amazon_appstore", isSideload = false),
    GalaxyStore("galaxy_store", isSideload = false),
    AppStore("app_store", isSideload = false),

    /**
     * Distribution through TestFlight. Not a sideload — it is a real device
     * running a real signed build — but not retail either; segment it out with
     * the `release_channel` resource attribute when that matters.
     */
    TestFlight("testflight", isSideload = false),

    /** An installer that is not one of the stores above. */
    Other("other", isSideload = true),

    /** No installer recorded at all: `adb install`, or a build with no store receipt. */
    None("none", isSideload = true),

    /** The platform lookup threw or is unavailable on this OS version. */
    Unknown("unknown", isSideload = false),
}

/**
 * Coarse hardware tier, mirroring the shape of Sentry's `device.class` so the
 * two surfaces can be read side by side. Thresholds are ours and deliberately
 * blunt — this exists to split "cheap phone" from "flagship" when reading a
 * latency or ANR distribution, not to identify a model.
 */
enum class DeviceClass(val value: String) {
    Low("low"),
    Medium("medium"),
    High("high"),
    Unknown("unknown"),
}

/**
 * Stable facts about this install and the hardware it runs on, resolved once
 * per process and stamped onto the OTel Resource at SDK init.
 *
 * The gap these close: prod client telemetry could not tell a retail install
 * from an emulator or a re-signed store build, so a single emulator ANR could
 * drag reported crash-free users to 94% and every all-time user count carried
 * an unknown amount of our own noise (ENG-38).
 *
 * **Tag, don't drop.** Nothing here suppresses a record. The noise still ships
 * and stays queryable; the dashboards choose whether to filter on it.
 */
data class InstallFacts(
    val source: InstallSource,
    val isEmulator: Boolean,
    val isRooted: Boolean,
    val deviceClass: DeviceClass,
    val osVersion: String,
) {
    val isSideloaded: Boolean get() = source.isSideload

    /**
     * The one attribute the dashboards filter on. False only for something we
     * positively identified as not a retail install — an unresolvable fact
     * leaves the install counted, so a broken lookup under-reports noise
     * rather than deleting real users.
     */
    val isGenuineInstall: Boolean get() = !isEmulator && !isSideloaded && !isRooted

    companion object {
        /** What a platform reports when every lookup failed. */
        val Unresolved = InstallFacts(
            source = InstallSource.Unknown,
            isEmulator = false,
            isRooted = false,
            deviceClass = DeviceClass.Unknown,
            osVersion = "unknown",
        )
    }
}

/**
 * Platform lookup for [InstallFacts]. Implementations resolve lazily and cache
 * — the answer cannot change within a process, and some of the inputs touch
 * the filesystem.
 */
interface InstallFactsProvider {
    fun facts(): InstallFacts
}

private const val BYTES_PER_GIB = 1024L * 1024L * 1024L

/**
 * Shared tiering so Android and iOS land on the same scale. Both inputs must
 * agree before a device is promoted: a 4-core device with 8GB is still a
 * device that stalls under load, and RAM alone reads high on tablets that are
 * otherwise slow.
 */
fun deviceClassFor(totalMemoryBytes: Long, processorCount: Int): DeviceClass = when {
    totalMemoryBytes <= 0L || processorCount <= 0 -> DeviceClass.Unknown
    totalMemoryBytes >= 6 * BYTES_PER_GIB && processorCount >= 8 -> DeviceClass.High
    totalMemoryBytes >= 3 * BYTES_PER_GIB && processorCount >= 4 -> DeviceClass.Medium
    else -> DeviceClass.Low
}
