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

    lint {
        // Lint had never run here. The first pass over `:apps:compose` found two
        // real NewApi errors — a StrictMode `Violation` (API 28) in a method
        // signature and `windowSplashScreenBackground` (API 31) outside a -v31
        // folder — against six warnings total, so the gate costs almost nothing
        // and is worth having on. Both were fixed rather than baselined; there
        // is deliberately no lint-baseline.xml, because an empty report is the
        // only one anybody reads.
        abortOnError = true
        warningsAsErrors = false
        // Reaches out to Maven Central on every run to ask whether a newer
        // version exists. That is a network call and a moving answer, which is
        // not something a build gate should depend on.
        disable += "NewerVersionAvailable"
        // The one that matters: calling an API above minSdk (24). Explicit
        // rather than inherited so disabling it is a deliberate act.
        // Note it does NOT catch every API-level mistake — see
        // AndroidPreviousExitProvider for the signature-resolution variant it
        // misses, which is guarded by a test instead.
        error += "NewApi"
        checkDependencies = false
    }
}