plugins {
    id("cards.compose.multiplatform")
    alias(libs.plugins.kotlinSerialization)
}

android {
    namespace = "com.dangerfield.cards.libraries.navigation"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api: Route.authRequirement / AuthGateRoute.reason expose core's
            // auth vocabulary to every module that declares routes.
            api(projects.libraries.core)
            implementation(projects.libraries.ui)
            implementation(projects.libraries.flowroutines)
            api(libs.jetbrains.navigation.compose)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}