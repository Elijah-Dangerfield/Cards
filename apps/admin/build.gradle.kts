import java.util.Properties

/**
 * The remote-config admin console: a Compose Multiplatform (web) app built on
 * Compose HTML / DOM. Deliberately NOT a `cards.*` convention-plugin module —
 * those force the Android + iOS targets, and this tool only ever runs in a
 * browser. Single `js` target, no shared client code.
 *
 * Hosted: each environment's server serves this bundle at `/admin` (the deploy
 * workflows build it and ship it alongside the server — see DEPLOY.md). Admin
 * tokens are NEVER baked into the bundle (public repo); the operator pastes
 * one at runtime and it lives in the browser's localStorage. For working on
 * the console itself use the "Admin Web" run config (or
 * `./gradlew :apps:admin:jsBrowserDevelopmentRun --continuous`).
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// ── Export the in-code config registry as a manifest CI uploads at release ────
// The admin tool's "what did 1.0.1 ship with" view reads a per-version manifest
// of the app's in-code config defaults. This task reads the committed registry
// (config-manifest-registry.json), validates it, stamps the CURRENT version
// (from versions.properties), and writes the upload payload; CI PUTs it to
// `/v1/admin/config/manifest` after a deploy (see README).
//
// The client DI graph that owns the live `Set<ConfiguredValue<*>>` is Android/
// iOS-only, so it can't be enumerated from this JS module. The registry is a
// maintained list of the scalar (targetable) flags — an androidUnitTest in
// :apps:integration (ConfigManifestDriftTest) fails if it drifts from the real
// ConfiguredValue classes. Composite (JsonConfigValue) flags are intentionally
// omitted — they aren't targeted per version/locale and their defaults are large.
val exportConfigManifest = tasks.register("exportConfigManifest") {
    description = "Validate config-manifest-registry.json and write the upload payload for CI."
    val versionsFile = rootProject.file("versions.properties")
    val registryFile = layout.projectDirectory.file("config-manifest-registry.json").asFile
    val outFile = layout.buildDirectory.file("config-manifest.json")
    inputs.file(versionsFile)
    inputs.file(registryFile)
    outputs.file(outFile)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val entries = groovy.json.JsonSlurper().parse(registryFile) as List<Map<String, Any?>>

        // Structural guard: a malformed/inconsistent registry fails the build (CI).
        val validTypes = setOf("boolean", "int", "long", "double", "string", "json")
        val seen = mutableSetOf<String>()
        entries.forEachIndexed { i, e ->
            val path = e["path"] as? String ?: throw GradleException("registry[$i]: missing 'path'")
            val type = e["type"] as? String ?: throw GradleException("$path: missing 'type'")
            if (type !in validTypes) throw GradleException("$path: invalid type '$type' (expected $validTypes)")
            if (!seen.add(path)) throw GradleException("$path: duplicate path")
            if (!e.containsKey("default")) throw GradleException("$path: missing 'default'")
            val default = e["default"]
            val typeOk = when (type) {
                "boolean" -> default is Boolean
                "int", "long" -> default is Int || default is Long || default is java.math.BigInteger
                "double" -> default is Number
                "string" -> default is String
                else -> true
            }
            if (!typeOk) throw GradleException("$path: default $default does not match type '$type'")
            @Suppress("UNCHECKED_CAST")
            val allowed = e["allowedValues"] as? List<Any?>
            if (allowed != null && default !in allowed) {
                throw GradleException("$path: default '$default' not in allowedValues $allowed")
            }
        }

        val props = Properties().apply { versionsFile.inputStream().use { load(it) } }
        val versionCode = props.getProperty("versionCode", "0").trim()
        val versionName = props.getProperty("versionName", "").trim()
        val out = outFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            """{"versionCode":$versionCode,"appVersion":"$versionName",""" +
                """"entries":${groovy.json.JsonOutput.toJson(entries)}}""" + "\n",
        )
        logger.lifecycle("Wrote config manifest for v$versionName ($versionCode), ${entries.size} flags → $out")
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
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
