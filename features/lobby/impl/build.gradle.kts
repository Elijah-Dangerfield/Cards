plugins {
    id("cards.feature")
}

android {
    namespace = "com.dangerfield.cards.features.lobby.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.lobby)
            // The entry point routes LobbyEvent.NavigateToMultiplayer
            // to PlayMultiplayerRoute defined in :features:room.
            implementation(projects.features.room)
            implementation(projects.libraries.rooms)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)
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
            implementation(libs.turbine)
        }
    }
}
