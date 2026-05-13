package com.dangerfield.cards.util

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

internal fun Project.configureKotlinMultiplatform(
    configureIosTarget: KotlinNativeTarget.() -> Unit = {}
) {
    extensions.configure<KotlinMultiplatformExtension> {
        androidTarget()

        listOf(
            iosArm64(),
            iosSimulatorArm64()
        ).forEach { iosTarget ->
            iosTarget.configureIosTarget()
        }

        sourceSets.apply {
            androidMain.dependencies {
                implementation(libs.androidx.activity.compose)
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