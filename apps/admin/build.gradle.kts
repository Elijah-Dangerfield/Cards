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
