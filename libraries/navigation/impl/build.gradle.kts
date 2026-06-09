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
            // Auth-gate: reads AuthState + guest-creation state to gate routes.
            implementation(projects.libraries.identity)
            // `:features:profile` api carries `BugReportRoute`, the
            // destination of the in-app error-fallback dialog.
            implementation(projects.features.profile)
            api(libs.jetbrains.navigation.compose)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(projects.libraries.flowroutines.testing)
            implementation(projects.libraries.navigation)
            implementation(projects.libraries.core)
            implementation(projects.libraries.identity)
        }
    }
}