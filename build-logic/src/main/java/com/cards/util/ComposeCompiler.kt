package com.dangerfield.cards.util

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/**
 * Turns on the Compose compiler's stability and skippability reports, behind a
 * flag.
 *
 * ```
 * ./gradlew :features:room:impl:assembleDebug -Pcards.composeReports=true
 * ```
 *
 * Output lands in each module's `build/compose-reports/`. Two files matter:
 *
 * - `*-composables.txt` — one entry per composable with each parameter marked
 *   `stable` or `unstable`. `restartable skippable fun Foo` is the good case.
 * - `*-classes.txt` — why a class came out unstable, field by field. Types from
 *   modules that don't apply the Compose compiler (`:libraries:gameplay`,
 *   `:libraries:bots`) are always reported unstable, because there is no
 *   inference to be had for them.
 *
 * **Read the report as a diagnostic, not a verdict.** Under strong skipping
 * (default since Kotlin 2.0.20) an "unstable" parameter is still skipped when
 * an equal instance is passed, so a wall of `unstable` in this report does not
 * by itself mean anything is recomposing more than it should — we measured
 * exactly that and found no cost. `ComposeStabilityTest` in `:features:room:impl`
 * asserts the behaviour the report only hints at, and is the thing to trust.
 *
 * Opt-in because it adds a compiler pass and writes a file per module: worth
 * minutes while hunting a recomposition, worth nothing the rest of the time.
 */
internal fun Project.configureComposeCompiler() {
    if (providers.gradleProperty("cards.composeReports").orNull != "true") return

    extensions.configure<ComposeCompilerGradlePluginExtension> {
        reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
        metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
    }
}
