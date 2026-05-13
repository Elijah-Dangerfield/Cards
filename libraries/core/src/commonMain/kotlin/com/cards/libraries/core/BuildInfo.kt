package com.dangerfield.cards.libraries.core

expect object BuildInfo {
    val isDebug: Boolean
    val platform: Platform
    val applicationId: String
    val versionName: String
    val versionCode: Int
    val releaseChannel: String
    val buildNumber: Int
}

fun BuildInfo.isiOS() = BuildInfo.platform == Platform.iOS
val BuildInfo.buildType: String get() = if (BuildInfo.isDebug) "debug" else "release"
val BuildInfo.versionTag: String get() = "${BuildInfo.versionName}-${BuildInfo.releaseChannel}"
fun BuildInfo.versionString(): String = "$versionName ($buildNumber)"


enum class Platform {
    Android,
    iOS
}