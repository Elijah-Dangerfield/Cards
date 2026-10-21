package com.dangerfield.cards.util

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * The API level `java.time` arrived in. Below it the platform has no
 * `java.time.*` at all, so the desugared backport has to be dexed in.
 */
private const val JAVA_TIME_API_LEVEL = 26

internal fun CommonExtension<*, *, *, *, *, *>.configureAndroid(project: Project) {
    compileSdk = SharedConstants.compileSdk

    defaultConfig {
        minSdk = SharedConstants.minSdk
    }

    compileOptions {
        val jvmTargetVersion = JavaVersion.toVersion(SharedConstants.jvmTarget)
        sourceCompatibility = jvmTargetVersion
        targetCompatibility = jvmTargetVersion
        // ENG-78 / CARDS-CS: `kotlinx-datetime` delegates to `java.time` on
        // Android, and `java.time` is API 26+. With minSdk 24 and no
        // desugaring, every formatting call was a fatal NoClassDefFoundError on
        // an API 24/25 device — it killed a multiplayer hand in 0.5.0. Keyed off
        // minSdk rather than hardcoded so raising the floor to 26 drops the
        // backport in the same move, instead of leaving dead weight in the dex.
        isCoreLibraryDesugaringEnabled = SharedConstants.minSdk < JAVA_TIME_API_LEVEL
    }

    if (SharedConstants.minSdk < JAVA_TIME_API_LEVEL) {
        project.dependencies {
            add("coreLibraryDesugaring", project.libs.desugar.jdkLibs)
        }
        project.failIfDesugaringGetsTurnedOff(this)
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
        // misses, and ENG-78 for the kotlinx-datetime delegation it also misses.
        // Both are guarded by a test instead.
        error += "NewApi"
        checkDependencies = false
    }
}

/**
 * The flag set above is only the default a module script can still override, and
 * nothing downstream notices: the build stays green and the crash reappears on
 * API 24/25 hardware nobody here owns. So re-read it once every script has had
 * its say, and refuse to build rather than ship that again.
 */
private fun Project.failIfDesugaringGetsTurnedOff(extension: CommonExtension<*, *, *, *, *, *>) {
    afterEvaluate {
        if (!extension.compileOptions.isCoreLibraryDesugaringEnabled) {
            error(
                "$path disables core library desugaring while minSdk is " +
                    "${SharedConstants.minSdk}. kotlinx-datetime delegates to java.time, " +
                    "which does not exist below API $JAVA_TIME_API_LEVEL, so this crashes on " +
                    "a real device (ENG-78). Either leave desugaring on or raise minSdk to " +
                    "$JAVA_TIME_API_LEVEL.",
            )
        }
    }
}
