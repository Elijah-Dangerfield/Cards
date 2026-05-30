plugins {
    id("cards.feature")
}

android {
    namespace = "com.dangerfield.cards.features.progression.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.progression)
            implementation(projects.features.profile)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.core)
            implementation(projects.libraries.identity)
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
            implementation(projects.libraries.cards)
            implementation(projects.libraries.identity)
            // FormatEarnedAgoTest exercises a public helper in :libraries:ui.
            // The commonMain `implementation(projects.libraries.ui)` above
            // doesn't propagate to commonTest under the Kotlin Compose MP
            // setup, so it has to be re-stated here.
            implementation(projects.libraries.ui)
            implementation(libs.turbine)
        }
    }
}
