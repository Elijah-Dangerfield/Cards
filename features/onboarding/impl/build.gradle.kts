plugins {
    id("cards.feature")
}

android {
    namespace = "com.dangerfield.cards.features.onboarding.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.onboarding)
            implementation(projects.features.home)
            implementation(projects.libraries.navigation)

            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.ui)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.identity)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
        }
        commonTest.dependencies {
            implementation(projects.libraries.flowroutines.testing)
            implementation(projects.features.onboarding)
        }
    }
}
