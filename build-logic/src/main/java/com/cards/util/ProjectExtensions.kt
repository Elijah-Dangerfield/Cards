package com.dangerfield.cards.util

import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project
import org.gradle.kotlin.dsl.provideDelegate
import org.gradle.kotlin.dsl.the
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.util.prefixIfNot
import java.util.Locale

internal val Project.libs get() = the<LibrariesForLibs>()


internal fun Project.optInKotlinMarkers(vararg markerClasses: String) {
  tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(markerClasses.map { "-opt-in=$it" })
  }
  
  // Also configure for Kotlin Multiplatform projects
  pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
      targets.configureEach {
        compilations.configureEach {
          compilerOptions.configure {
            freeCompilerArgs.addAll(markerClasses.map { "-opt-in=$it" })
          }
        }
      }
    }
  }
}

/**
 * Get the module or submodule as a [Dependency]
 */
internal fun DependencyHandler.getModule(name: String, submodule: String? = null) =
  project(":$name${submodule?.let { ":$submodule" } ?: ""}")


fun Project.configureKotlinInject() {
  project.pluginManager.apply("com.google.devtools.ksp")

  project.extensions.configure<KotlinMultiplatformExtension>() {
      sourceSets.apply {
        commonMain.dependencies {
          implementation(libs.kotlin.inject.runtime.kmp)
          implementation(libs.anvil.runtime)
          implementation(libs.anvil.runtime.optional)
        }
      }

    this@configureKotlinInject.dependencies {
      val kspTargets by lazy {
        this@configure.targets.names.map { it.capitalizeUS() }
          .map {
            val name = if (it == "Metadata") "CommonMainMetadata" else it
            name.prefixIfNot("ksp")
          }
      }

      kspTargets.forEach { target ->
        addProvider(target, libs.kotlin.inject.compiler.ksp)
        addProvider(target, libs.anvil.compiler)
      }
    }
  }
}

fun Project.addKspDependencyForAllTargets(dependency: Provider<MinimalExternalModuleDependency>) {
  pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
      val kspTargets = targets.names.map { it.capitalizeUS() }.map {
        val normalized = if (it == "Metadata") "CommonMainMetadata" else it
        normalized.prefixIfNot("ksp")
      }

      this@addKspDependencyForAllTargets.dependencies {
        kspTargets.forEach { target ->
          addProvider(target, dependency)
        }
      }
    }
  }
}


internal fun String.capitalizeUS() = replaceFirstChar {
  if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
}

