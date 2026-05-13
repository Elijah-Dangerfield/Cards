package com.dangerfield.cards.util

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion

internal fun CommonExtension<*, *, *, *, *, *>.configureAndroid() {
    compileSdk = SharedConstants.compileSdk

    defaultConfig {
        minSdk = SharedConstants.minSdk
    }

    compileOptions {
        val jvmTargetVersion = JavaVersion.toVersion(SharedConstants.jvmTarget)
        sourceCompatibility = jvmTargetVersion
        targetCompatibility = jvmTargetVersion
    }

    buildFeatures {
        buildConfig = true
    }
}