plugins {
    id("cards.feature")
}

android {
    namespace = "com.dangerfield.cards.features.rooms.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.rooms)
            // Public shells are stateless/backend-less — no :libraries:rooms
            // (the multiplayer socket layer) until matchmaking lands.
            implementation(projects.libraries.cards)
            implementation(projects.libraries.core)
            implementation(projects.libraries.ui)
            implementation(projects.libraries.navigation)
            implementation(projects.libraries.resources)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
        }
    }
}
