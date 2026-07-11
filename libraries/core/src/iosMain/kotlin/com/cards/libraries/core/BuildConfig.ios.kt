package com.dangerfield.cards.libraries.core

import com.dangerfield.cards.buildinfo.CardsBuildConfig
import platform.Foundation.NSBundle
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform as NativePlatform

@OptIn(ExperimentalNativeApi::class)
actual object BuildInfo {
    actual val isDebug: Boolean
        get() = NativePlatform.isDebugBinary

    actual val platform: Platform = Platform.iOS

    actual val applicationId: String
        get() = CardsBuildConfig.APPLICATION_ID

    actual val versionName: String
        get() = CardsBuildConfig.VERSION_NAME

    actual val versionCode: Int
        get() = CardsBuildConfig.VERSION_CODE

    actual val releaseChannel: String
        get() = CardsBuildConfig.RELEASE_CHANNEL

    actual val buildNumber: Int
        get() = CardsBuildConfig.BUILD_NUMBER

    actual val commitSha: String
        get() = CardsBuildConfig.COMMIT_SHA

    actual val commitBranch: String
        get() = CardsBuildConfig.COMMIT_BRANCH

    // TestFlight installs get a sandbox receipt; App Store installs get a
    // production one. The URL is present even before StoreKit fetches the
    // receipt file, so reading its last path component is cheap and safe.
    // Debug (Xcode-run) builds can also carry a sandbox receipt, so gate on
    // !isDebug to keep the flag meaning strictly "TestFlight distribution".
    actual val isTestFlight: Boolean by lazy {
        !isDebug && NSBundle.mainBundle.appStoreReceiptURL?.lastPathComponent == "sandboxReceipt"
    }
}