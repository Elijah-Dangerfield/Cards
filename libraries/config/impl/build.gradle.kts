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
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(compose.components.resources)
        }
    }
}

compose.resources {
    publicResClass = false
}