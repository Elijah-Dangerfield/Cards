package com.dangerfield.cards.libraries.core

import com.dangerfield.cards.buildinfo.CardsBuildConfig
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
}