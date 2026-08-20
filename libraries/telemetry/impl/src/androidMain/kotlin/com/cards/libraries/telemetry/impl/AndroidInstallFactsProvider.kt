package com.dangerfield.cards.libraries.telemetry.impl

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.dangerfield.cards.libraries.core.Catching
import java.io.File
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AndroidInstallFactsProvider(
    private val context: Context,
) : InstallFactsProvider {

    private val facts: InstallFacts by lazy {
        Catching {
            InstallFacts(
                source = installSource(),
                isEmulator = isEmulatorBuild(
                    fingerprint = Build.FINGERPRINT,
                    model = Build.MODEL,
                    manufacturer = Build.MANUFACTURER,
                    brand = Build.BRAND,
                    device = Build.DEVICE,
                    product = Build.PRODUCT,
                    hardware = Build.HARDWARE,
                ),
                isRooted = isRooted(),
                deviceClass = deviceClassFor(totalMemoryBytes(), Runtime.getRuntime().availableProcessors()),
                osVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { Build.VERSION.SDK_INT.toString() },
            )
        }.getOrDefault(InstallFacts.Unresolved)
    }

    override fun facts(): InstallFacts = facts

    private fun installSource(): InstallSource = Catching {
        val packageManager = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(context.packageName)
        }
        installSourceForPackage(installer)
    }.getOrDefault(InstallSource.Unknown)

    private fun totalMemoryBytes(): Long = Catching {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo).totalMem
    }.getOrDefault(0L)

    private fun isRooted(): Boolean = Catching {
        Build.TAGS?.contains(DEV_KEYS_TAG) == true || SU_PATHS.any { File(it).exists() }
    }.getOrDefault(false)

    private companion object {
        const val DEV_KEYS_TAG = "test-keys"

        val SU_PATHS = listOf(
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/data/local/bin/su",
            "/data/local/xbin/su",
            "/su/bin/su",
            "/system/app/Superuser.apk",
        )
    }
}

/**
 * The installing package as reported by `PackageManager`, folded into the
 * closed [InstallSource] set. A null installer means nothing recorded one —
 * `adb install` and most sideload flows.
 */
internal fun installSourceForPackage(installerPackage: String?): InstallSource = when (installerPackage) {
    null, "" -> InstallSource.None
    "com.android.vending" -> InstallSource.PlayStore
    "com.amazon.venezia", "com.amazon.mShop.android.shopping" -> InstallSource.AmazonAppstore
    "com.sec.android.app.samsungapps" -> InstallSource.GalaxyStore
    else -> InstallSource.Other
}

/**
 * Emulator detection from `Build` fields only, so it stays a pure function the
 * unit tests can drive with real device and real AVD fingerprints.
 *
 * No single field is decisive — Google's own AVD images have shipped
 * retail-looking models (`sdk_gphone64_arm64`) — so this is a union of the
 * signals that have held across image generations: the goldfish/ranchu/vbox
 * kernels, the `generic` brand+device pair, and the SDK product/model names.
 */
internal fun isEmulatorBuild(
    fingerprint: String,
    model: String,
    manufacturer: String,
    brand: String,
    device: String,
    product: String,
    hardware: String,
): Boolean {
    val emulatedKernel = hardware.lowercase() in EMULATED_HARDWARE
    val genericBuild = fingerprint.startsWith("generic") ||
        fingerprint.contains("vbox") ||
        fingerprint.contains("emulator") ||
        (brand.startsWith("generic") && device.startsWith("generic"))
    val sdkImage = product == "google_sdk" ||
        product.startsWith("sdk_") ||
        product == "sdk" ||
        model.contains("google_sdk") ||
        model.contains("Emulator") ||
        model.startsWith("Android SDK built for") ||
        model.startsWith("sdk_gphone")
    return emulatedKernel || genericBuild || sdkImage || manufacturer.contains("Genymotion")
}

private val EMULATED_HARDWARE = setOf("goldfish", "ranchu", "vbox86", "gce_x86", "cutf_cvm")
