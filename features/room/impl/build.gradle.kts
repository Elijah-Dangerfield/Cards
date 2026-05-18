plugins {
    id("cards.feature")
}

android {
    namespace = "com.dangerfield.cards.features.room.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.room)
            implementation(projects.features.progression)
            implementation(projects.libraries.bots)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.gameplay)
            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.ui)
            implementation(projects.libraries.navigation)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
        }
    }
}
