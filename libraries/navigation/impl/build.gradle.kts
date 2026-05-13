plugins {
    id("cards.compose.multiplatform")
    alias(libs.plugins.kotlinSerialization)
}

android {
    namespace = "com.dangerfield.cards.libraries.navigation.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.libraries.core)
            implementation(projects.libraries.navigation)
            implementation(projects.libraries.ui)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.cards)
            api(libs.jetbrains.navigation.compose)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}