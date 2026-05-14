plugins {
    id("cards.feature")
}

android {
    namespace = "com.dangerfield.cards.features.upgrade.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.upgrade)
            implementation(projects.libraries.config)
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
