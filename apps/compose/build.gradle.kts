plugins {
    id("cards.application")
    id("co.touchlab.skie") version "0.10.13"
    alias(libs.plugins.baselineProfile)
    alias(libs.plugins.sentryAndroid)
}

android {
    namespace = "com.dangerfield.cards"
}

/**
 * Consume the committed Baseline Profile rather than regenerating it on every
 * release build — generation starts an emulator and walks the app, which is
 * minutes nobody wants in the release path.
 *
 * Regenerate deliberately when the app's shape changes:
 * `./gradlew :apps:compose:generateBaselineProfile`
 */
baselineProfile {
    automaticGenerationDuringBuild = false
}

/**
 * Sentry's Android Gradle plugin, for exactly one job: making obfuscated crash
 * reports readable.
 *
 * R8 renames methods, so from the first minified release every Sentry frame
 * arrives as `a.b.c`. Deobfuscating needs two things — the mapping file
 * uploaded, and a UUID in the manifest tying that mapping to this build. The
 * release workflow already ran `sentry-cli upload-proguard`, but the app had no
 * UUID for it to match against, so the upload associated with nothing and said
 * it succeeded. This plugin injects the UUID.
 */
sentry {
    org.set(providers.environmentVariable("SENTRY_ORG"))
    projectName.set(providers.environmentVariable("SENTRY_PROJECT"))
    authToken.set(providers.environmentVariable("SENTRY_AUTH_TOKEN"))

    // The app already uses the Kotlin Multiplatform Sentry SDK. Auto-installation
    // would add `sentry-android` on top of it, and two SDKs initialising in one
    // process is not a thing to discover in production.
    autoInstallation { enabled.set(false) }

    // Always inject the UUID: it is what makes a mapping associable at all, and
    // it costs nothing in a build without a token.
    includeProguardMapping.set(true)

    // Only upload when a token exists, so a contributor can still build a
    // release locally without one. CI has it (release.yml job env).
    autoUploadProguardMapping.set(
        providers.environmentVariable("SENTRY_AUTH_TOKEN").isPresent,
    )

    // No build-time telemetry to Sentry about our Gradle builds.
    telemetry.set(false)
}

dependencies {
    baselineProfile(projects.apps.baselineprofile)
}

kotlin {

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.androidx.work.runtime)
            implementation(compose.uiTooling)
        }

        commonMain.dependencies {
            // Project dependencies
            api(projects.libraries.core)
            implementation(projects.libraries.ui)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.cards.impl)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.bots)
            implementation(projects.libraries.gameplay)
            implementation(projects.libraries.navigation)
            implementation(projects.libraries.navigation.impl)
            implementation(projects.libraries.resources)

            implementation(projects.libraries.storage)
            implementation(projects.libraries.storage.impl)
            implementation(projects.libraries.cards.storage)
            implementation(projects.libraries.config)
            implementation(projects.libraries.config.impl)
            implementation(projects.libraries.cards.storage)
            implementation(projects.libraries.networking)
            implementation(projects.libraries.networking.impl)
            implementation(projects.libraries.products)
            implementation(projects.libraries.products.impl)
            implementation(projects.libraries.billing)
            implementation(projects.libraries.billing.impl)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.identity.impl)
            implementation(projects.libraries.social)
            implementation(projects.libraries.social.impl)
            implementation(projects.libraries.rooms)
            implementation(projects.libraries.rooms.impl)
            implementation(projects.libraries.review)
            implementation(projects.libraries.review.impl)
            implementation(projects.libraries.telemetry.impl)

            implementation(projects.features.home)
            implementation(projects.features.home.impl)
            implementation(projects.features.lobby)
            implementation(projects.features.lobby.impl)
            implementation(projects.features.onboarding)
            implementation(projects.features.onboarding.impl)
            implementation(projects.features.profile)
            implementation(projects.features.profile.impl)
            implementation(projects.features.progression)
            implementation(projects.features.progression.impl)
            implementation(projects.features.room)
            implementation(projects.features.room.impl)
            implementation(projects.features.rooms)
            implementation(projects.features.rooms.impl)
            implementation(projects.features.shop)
            implementation(projects.features.shop.impl)
            implementation(projects.features.upgrade)
            implementation(projects.features.upgrade.impl)

            implementation(libs.atomicfu)
            
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
        }

        commonTest.dependencies {
            implementation(projects.libraries.flowroutines.testing)
        }
    }
}
// Composition tracing: makes @Composable function names show up as named slices
// in a Perfetto / Android Studio system trace, so a slow frame can be attributed
// to a composable instead of to "Compose".
//
// Added for ENG-49, where four production ANRs put the RenderThread inside
// Skia's glyph cache under a text draw and the open question is *which* text.
// Guessing at the answer already cost one wrong diagnosis; this names it.
//
// Debug-only, in the top-level dependencies block because the `debugImplementation`
// configuration only exists on the Android target (same reason as Wiretap in
// :libraries:networking:impl). Nothing reaches a release build.
dependencies {
    debugImplementation(libs.androidx.compose.runtimeTracing)
    // runtime-tracing alone ships the API, not the native library it dlopens.
    // Android Studio sideloads that for you when you tick "composition tracing",
    // which is why a Studio capture works without these — but a plain
    // `adb shell perfetto` capture fails with UnsatisfiedLinkError on
    // libtracing_perfetto.so. Bundling them makes tracing work from the command
    // line too, which is what a repeatable before/after measurement needs.
    debugImplementation(libs.androidx.tracing.perfetto)
    debugImplementation(libs.androidx.tracing.perfettoBinary)
}
