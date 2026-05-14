plugins {
    id("cards.feature")
}

android {
    namespace = "com.dangerfield.cards.features.home.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.home)
            implementation(projects.features.progression)
            implementation(projects.features.room)
            implementation(projects.features.shop)
            implementation(projects.libraries.navigation)

            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.ui)
            implementation(projects.libraries.cards)

            // Compose dependencies (navigation and lifecycle provided by cards.feature plugin)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
        }
    }
}