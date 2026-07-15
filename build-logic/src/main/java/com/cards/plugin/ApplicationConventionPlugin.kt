package com.dangerfield.cards.plugin

import com.android.build.api.dsl.ApplicationExtension
import com.dangerfield.cards.ext.ConfigurationExtension
import com.dangerfield.cards.util.SharedConstants
import com.dangerfield.cards.util.configureAndroid
import com.dangerfield.cards.util.configureKotlinInject
import com.dangerfield.cards.util.configureKotlinMultiplatform
import com.dangerfield.cards.util.configureReleaseSigning
import com.dangerfield.cards.util.enforceModuleBoundaries
import com.dangerfield.cards.util.libs
import com.dangerfield.cards.util.verifyGitHooksInstalled
import com.dangerfield.cards.util.loadVersionMetadata
import com.dangerfield.cards.util.optInKotlinMarkers
import com.dangerfield.cards.util.VersionMetadata
import com.dangerfield.cards.util.writeCommonMetadata
import com.github.gmazzo.buildconfig.BuildConfigExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Convention plugin for the main Android application module.
 *
 * **When to use this plugin:**
 * - The main app module that gets installed on devices
 * - The module that contains MainActivity and app-level configuration
 * - The module that defines the applicationId and app metadata
 *
 * **What this plugin provides:**
 * - Android application plugin configuration
 * - Kotlin Multiplatform setup with Android and iOS targets
 * - Compose and Compose Compiler plugins
 * - iOS framework configuration for KMP
 * - Application-specific build configuration (version codes, signing, etc.)
 * - Activity Compose dependencies
 *
 * **Examples of modules that should use this:**
 * - apps:compose (your main app)
 * - apps:desktop (if you have a desktop app variant)
 *
 * **Don't use this plugin for:**
 * - Feature modules (use cards.feature instead)
 * - Library modules (use cards.compose.multiplatform or cards.kotlin.multiplatform)
 * - Server modules (these wouldn't be Android applications)
 */
class ApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val versionMetadata = loadVersionMetadata()
            with(pluginManager) {
                apply("org.jetbrains.kotlin.multiplatform")
                apply("com.android.application")
                apply("org.jetbrains.compose")
                apply("org.jetbrains.kotlin.plugin.compose")
                apply(libs.plugins.kotlinSerialization.get().pluginId)
                apply(libs.plugins.buildconfig.get().pluginId)
            }

            project.optInKotlinMarkers("kotlin.time.ExperimentalTime")
            project.optInKotlinMarkers("kotlin.uuid.ExperimentalUuidApi")

            configureKotlinMultiplatform {
                binaries.framework {
                    baseName = "ComposeApp"
                    isStatic = true
                    binaryOption("bundleId", "com.dangerfield.cards")
                    export(project(":libraries:core"))
                }
            }
            configureKotlinInject()

            extensions.configure<ApplicationExtension> {
                configureAndroid()

                defaultConfig {
                    applicationId = versionMetadata.applicationId
                    targetSdk = SharedConstants.targetSdk
                    versionCode = versionMetadata.versionCode
                    versionName = versionMetadata.versionName
                }

                packaging {
                    resources {
                        excludes += "/META-INF/{AL2.0,LGPL2.1}"
                    }
                }

                val releaseSigning = configureReleaseSigning(this)

                buildTypes {
                    debug {
                        applicationIdSuffix = ".debug"
                    }
                    release {
                        isMinifyEnabled = false
                        signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
                    }
                }
            }

            if (extensions.findByName("moduleConfig") == null) {
                extensions.create("moduleConfig", ConfigurationExtension::class.java)
            }
            configureAppBuildConfig(versionMetadata)

            verifyGitHooksInstalled()
            enforceModuleBoundaries()
        }
    }

    private fun Project.configureAppBuildConfig(metadata: VersionMetadata) {
        extensions.configure(BuildConfigExtension::class.java) {
            packageName("${metadata.applicationId}.appconfig")
            className("AppBuildConfig")
            useKotlinOutput {
                internalVisibility = false
            }
            writeCommonMetadata(metadata)
        }
    }
}