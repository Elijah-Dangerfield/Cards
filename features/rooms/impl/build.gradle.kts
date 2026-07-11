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
            // The Searching VM hands off into PlayMultiplayerRoute (defined in
            // :features:room) once the table deals.
            implementation(projects.features.room)
            // Matchmaking + room socket layer (live since Phase 5).
            implementation(projects.libraries.rooms)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.flowroutines)
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
        commonTest.dependencies {
            implementation(projects.libraries.flowroutines.testing)
            implementation(projects.libraries.rooms)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.core)
            implementation(libs.turbine)
        }
    }
}
