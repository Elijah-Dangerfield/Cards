plugins {
    id("cards.kotlin.multiplatform")
}

android {
    namespace = "com.dangerfield.cards.libraries.flowroutines.testing"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // This module is consumed from other modules' `commonTest` source
            // sets, but the code lives in `commonMain` so it's an actual KMP
            // library (no commonTest cross-module sharing in KMP). Consumers
            // depend on it via `implementation` in their `commonTest`.
            api(libs.kotlinx.coroutines.test)
            api(libs.turbine)
            // commonMain needs the kotlin-test annotation API (BeforeTest /
            // AfterTest / Test). The per-platform binding (JUnit on JVM /
            // Android) comes from the consumer's commonTest, which already
            // pulls in libs.kotlin.test via the convention plugin.
            api(kotlin("test-annotations-common"))
            api(kotlin("test-common"))
            api(projects.libraries.flowroutines)
        }
        androidMain.dependencies {
            api(kotlin("test-junit"))
        }
    }
}
