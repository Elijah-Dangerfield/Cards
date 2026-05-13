package com.dangerfield.cards.plugin

import com.dangerfield.cards.ext.ConfigurationExtension
import com.dangerfield.cards.util.configureKotlinInject
import com.dangerfield.cards.util.libs
import com.dangerfield.cards.util.optInKotlinMarkers
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Convention plugin for feature modules that contain UI components, screens, and business logic.
 *
 * **When to use this plugin:**
 * - Feature modules that contain Compose UI screens and components
 * - Modules that need navigation capabilities (screens, flows)
 * - Modules with ViewModels and UI state management
 * - Feature modules that represent complete user-facing functionality
 *
 * **What this plugin provides:**
 * - Full Compose Multiplatform setup (via ComposeMultiplatformConventionPlugin)
 * - Common navigation dependencies (Navigation Compose)
 * - ViewModel and lifecycle dependencies
 * - Standard feature module dependencies
 *
 * **Examples of modules that should use this:**
 * - features:authentication
 * - features:home
 * - features:profile
 * - features:activity
 *
 * **Don't use this plugin for:**
 * - Pure UI component libraries (use cards.compose.multiplatform instead)
 * - Pure Kotlin libraries without UI (use cards.kotlin.multiplatform instead)
 * - The main application module (use cards.android.application instead)
 */
class FeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("cards.compose.multiplatform")
                apply(libs.plugins.kotlinSerialization.get().pluginId)
            }

            project.optInKotlinMarkers("kotlin.time.ExperimentalTime")
            project.optInKotlinMarkers("kotlin.uuid.ExperimentalUuidApi")

            // Add common feature dependencies
            extensions.configure<KotlinMultiplatformExtension> {

                sourceSets.apply {
                    commonMain.dependencies {
                        implementation(libs.androidx.lifecycle.viewmodelCompose)
                        implementation(libs.androidx.lifecycle.runtimeCompose)
                        implementation(libs.kotlinx.serialization.json)

                        implementation(project(":libraries:ui"))
                        implementation(project(":libraries:resources"))
                        implementation(project(":libraries:navigation"))
                    }

                    androidMain.dependencies {

                    }
                }
            }


            if (extensions.findByName("moduleConfig") == null) {
                extensions.create("moduleConfig", ConfigurationExtension::class.java)
            }
        }
    }
}