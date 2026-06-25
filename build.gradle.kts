plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.sentryKmp) apply false
    alias(libs.plugins.kotlinCocoapods) apply false
    // Declared here (version only) so the conditional block below can apply it.
    alias(libs.plugins.detekt) apply false
}

// ENG-2 — one root-level detekt task lints every module's Kotlin source with the
// custom :detekt-rules ruleset, behind a baseline, and hangs off `check`. A single
// source-scanning task (detekt's "plain" task) sidesteps per-module KMP source-set
// wiring. Skipped in the server-only Docker build, where :detekt-rules isn't on the
// build graph (it's a client/dev module) and linting isn't wanted anyway.
if (System.getProperty("cards.serverOnly") != "true") {
    apply(plugin = "dev.detekt")
    apply(plugin = "base")

    dependencies {
        "detektPlugins"(project(":detekt-rules"))
    }

    configure<dev.detekt.gradle.extensions.DetektExtension> {
        parallel.set(true)
        buildUponDefaultConfig.set(false)
        // Run ONLY the custom `cards` ruleset — not detekt's hundreds of built-in
        // rules, which would flag the whole codebase. New rules are added in
        // :detekt-rules, not by turning defaults on.
        disableDefaultRuleSets.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        baseline.set(rootProject.file("config/detekt/baseline.xml"))
        source.setFrom(subprojects.map { it.projectDir.resolve("src") })
    }

    tasks.named("check") { dependsOn("detekt") }
}