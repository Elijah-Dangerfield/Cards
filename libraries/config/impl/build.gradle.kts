plugins {
    id("cards.kotlin.multiplatform")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

moduleConfig {
    di()
    serialization()
}

android {
    namespace = "com.dangerfield.cards.libraries.config.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.libraries.config)
            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.networking)
            implementation(projects.libraries.storage)
            // SessionTracker — drives session-aware refresh on cold boot
            // and ≥15-min-background rollover, replacing the previous
            // fixed-interval polling loop.
            implementation(projects.libraries.cards)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(compose.components.resources)
        }
        commonTest.dependencies {
            implementation(projects.libraries.flowroutines.testing)
            implementation(projects.libraries.config)
            implementation(projects.libraries.core)
            implementation(projects.libraries.networking)
            implementation(projects.libraries.storage)
            implementation(projects.libraries.cards)
        }
    }
}

compose.resources {
    publicResClass = false
}