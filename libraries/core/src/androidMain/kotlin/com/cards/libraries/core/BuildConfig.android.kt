package com.dangerfield.cards.libraries.core

import com.dangerfield.cards.buildinfo.CardsBuildConfig
import com.dangerfield.cards.libraries.core.BuildConfig as AndroidBuildConfig

actual object BuildInfo {
    actual val isDebug: Boolean
        get() = AndroidBuildConfig.DEBUG

    actual val platform: Platform
        get() = Platform.Android

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