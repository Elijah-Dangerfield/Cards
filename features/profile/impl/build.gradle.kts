plugins {
    id("cards.feature")
}

android {
    namespace = "com.dangerfield.cards.features.profile.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.home)
            implementation(projects.features.profile)
            implementation(projects.features.progression)
            implementation(projects.features.upgrade)
            implementation(projects.features.onboarding)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.config)
            implementation(projects.libraries.products)
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
        commonTest.dependencies {
            implementation(projects.libraries.flowroutines.testing)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.config)
            implementation(libs.turbine)
        }
    }
}
