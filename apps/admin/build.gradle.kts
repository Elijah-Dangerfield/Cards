import java.util.Properties

/**
 * The local remote-config admin GUI: a Compose Multiplatform (web) app built on
 * Compose HTML / DOM. Deliberately NOT a `cards.*` convention-plugin module —
 * those force the Android + iOS targets, and this tool only ever runs in a
 * browser. Single `js` target, no shared client code, no shipping.
 *
 * Run it via the "Admin Web" run config (or
 * `./gradlew :apps:admin:jsBrowserDevelopmentRun --continuous`). In-page you
 * just pick Dev or Prod and hit Connect — the admin tokens are baked into the
 * bundle at build time from the gitignored `admin-tokens.local.properties`
 * (like a React `.env`). See the module README.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// ── Bake the dev/prod admin tokens into the bundle at build time ──────────────
// Read from the gitignored properties file (the React-`.env` equivalent) and
// generate `AdminEnv` with the URLs + tokens. A missing file or key → blank
// token, and the UI tells you to add it. The browser never reads a file; the
// build hands the values to the bundle.
val adminTokensFile = layout.projectDirectory.file("admin-tokens.local.properties")
val generatedAdminEnvDir = layout.buildDirectory.dir("generated/adminEnv/kotlin")

val generateAdminEnv = tasks.register("generateAdminEnv") {
    val propsFile = adminTokensFile.asFile
    val outDir = generatedAdminEnvDir
    inputs.file(propsFile).withPropertyName("adminTokensFile").optional(true)
    outputs.dir(outDir)
    doLast {
        val props = Properties()
        if (propsFile.exists()) propsFile.inputStream().use { props.load(it) }
        fun token(key: String): String = props.getProperty(key, "").trim()
            .replace("\\", "\\\\").replace("\"", "\\\"")
        val out = outDir.get().file("com/dangerfield/cards/admin/AdminEnv.kt").asFile
        out.parentFile.mkdirs()
        out.writeText(
            """
            package com.dangerfield.cards.admin

            // GENERATED at build time from apps/admin/admin-tokens.local.properties.
            // Do not edit by hand. Tokens are baked in for this local-only dev tool;
            // a blank token means the key is missing from that gitignored file.
            internal enum class AdminEnv(
                val displayName: String,
                val baseUrl: String,
                val token: String,
            ) {
                Dev("dev", "https://cards-server-dev.fly.dev", "${token("dev")}"),
                Prod("prod", "https://cards-server-prod.fly.dev", "${token("prod")}"),
            }
            """.trimIndent() + "\n",
        )
    }
}

// ── Export the in-code config registry as a manifest CI uploads at release ────
// The admin tool's "what did 1.0.1 ship with" view reads a per-version manifest
// of the app's in-code config defaults. This task emits that manifest for the
// CURRENT build (version stamped from versions.properties); CI PUTs it to
// `/v1/admin/config/manifest` after a release build (see README).
//
// The client DI graph that owns the live `Set<ConfiguredValue<*>>` is Android/
// iOS-only, so it can't be enumerated from this JS module. Instead this is a
// maintained registry of the scalar (targetable) flags — KEEP IN SYNC with:
//   libraries/social/.../SocialConfigValues.kt
//   libraries/identity/.../IdentityConfigValues.kt
//   libraries/identity/.../OnboardingConfigValues.kt
//   features/upgrade/.../UpgradeConfigValues.kt
// Composite (JsonConfigValue) flags are intentionally omitted — they aren't
// targeted per version/locale and their defaults are large objects.
val exportConfigManifest = tasks.register("exportConfigManifest") {
    description = "Write the per-version config manifest CI uploads to /v1/admin/config/manifest."
    val versionsFile = rootProject.file("versions.properties")
    val outFile = layout.buildDirectory.file("config-manifest.json")
    // Locals (not script properties) so the closure is configuration-cache safe.
    val entries = listOf(
        """{"path":"social.enabled","type":"boolean","default":false,"description":"Social features master switch"}""",
        """{"path":"identity.googleSignInEnabled","type":"boolean","default":false,"description":"Show Google sign-in"}""",
        """{"path":"identity.appleSignInEnabled","type":"boolean","default":true,"description":"Show Apple sign-in"}""",
        """{"path":"upgrade.minSupportedVersionCode","type":"int","default":1,"description":"Below this build code, force upgrade"}""",
        """{"path":"upgrade.maintenanceMode","type":"string","default":"off","description":"Maintenance gate","allowedValues":["off","banner","blocking"]}""",
        """{"path":"upgrade.maintenanceMessage","type":"string","default":"We're updating the servers, back in a moment.","description":"Maintenance banner/blocking copy"}""",
        """{"path":"onboarding.starterGrant","type":"long","default":0,"description":"Starter coin grant (0 = unknown sentinel)"}""",
        """{"path":"onboarding.suggestedName","type":"string","default":"","description":"Suggested display name (empty = none)"}""",
    )
    inputs.file(versionsFile)
    outputs.file(outFile)
    doLast {
        val props = Properties().apply { versionsFile.inputStream().use { load(it) } }
        val versionCode = props.getProperty("versionCode", "0").trim()
        val versionName = props.getProperty("versionName", "").trim()
        val out = outFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            """{"versionCode":$versionCode,"appVersion":"$versionName",""" +
                """"entries":[${entries.joinToString(",")}]}""" + "\n",
        )
        logger.lifecycle("Wrote config manifest for v$versionName ($versionCode) → $out")
    }
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "cards-config-admin.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val jsMain by getting {
            kotlin.srcDir(files(generatedAdminEnvDir).builtBy(generateAdminEnv))
            dependencies {
                implementation(compose.runtime)
                implementation(compose.html.core)

                implementation(libs.ktor.client.js)
                implementation(libs.ktor.client.contentNegotiation)
                implementation(libs.ktor.serialization.kotlinx.json)

                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
