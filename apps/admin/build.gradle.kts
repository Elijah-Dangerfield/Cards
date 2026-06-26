/**
 * The local remote-config admin GUI: a Compose Multiplatform (web) app built on
 * Compose HTML / DOM. Deliberately NOT a `cards.*` convention-plugin module —
 * those force the Android + iOS targets, and this tool only ever runs in a
 * browser. Single `js` target, no shared client code, no shipping.
 *
 * Run it: `./gradlew :apps:admin:jsBrowserDevelopmentRun --continuous` (hot
 * reload) or `:apps:admin:jsBrowserRun`. It serves on localhost; set the in-page
 * "Server URL" (defaults to http://localhost:8080) and paste the ADMIN_API_TOKEN.
 * Kill the Gradle task when you're done — nothing is deployed.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
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
    }
}
