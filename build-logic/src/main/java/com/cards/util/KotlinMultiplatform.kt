package com.dangerfield.cards.util

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/**
 * Server-only build flag — passed by apps/server/Dockerfile as
 * `-Dcards.serverOnly=true`. When set, the KMP convention plugins skip
 * the Android + iOS targets (the Docker image doesn't carry the Android
 * SDK or Kotlin/Native toolchain). The per-library `jvm { ... }` block
 * still supplies the JVM target. See settings.gradle.kts for the rest of
 * the gating story.
 */
internal val isServerOnlyBuild: Boolean
    get() = System.getProperty("cards.serverOnly") == "true"

internal fun Project.configureKotlinMultiplatform(
    configureIosTarget: KotlinNativeTarget.() -> Unit = {}
) {
    val serverOnly = isServerOnlyBuild
    extensions.configure<KotlinMultiplatformExtension> {
        if (!serverOnly) {
            androidTarget()

            listOf(
                iosArm64(),
                iosSimulatorArm64()
            ).forEach { iosTarget ->
                iosTarget.configureIosTarget()
            }
        }

        sourceSets.apply {
            if (!serverOnly) {
                androidMain.dependencies {
                    implementation(libs.androidx.activity.compose)
                }
            }
            commonMain.dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
            commonTest.dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}