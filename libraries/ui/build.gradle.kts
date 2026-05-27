
plugins {
    id("cards.compose.multiplatform")
}

android {
    namespace = "com.dangerfield.cards.libraries.ui"
}

kotlin {
    sourceSets {

        androidMain.dependencies {
            api(compose.preview)
            api(compose.uiTooling)
        }

        commonMain.dependencies {
            implementation(projects.libraries.core)
            // DispatcherProvider so platform AudioRecorder / PhotoSaver
            // impls route I/O through the injected dispatcher instead of
            // grabbing Dispatchers.IO directly (per AGENTS.md coroutines
            // rule — production code never reaches for raw Dispatchers).
            implementation(projects.libraries.flowroutines)
            // TODO honestly the cards library should expose the component that require cards domain
            implementation(projects.libraries.cards)
            // Poker game types (Card, Rank, Suit) for components/poker. These
            // are pure data classes — no engine, no Compose — so they're safe
            // to pull into the DS.
            api(projects.libraries.gameplay)

            api(compose.ui)
            api(compose.uiUtil)
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.components.resources)
            api(compose.components.uiToolingPreview)
            api(compose.materialIconsExtended)
            api(compose.material3AdaptiveNavigationSuite)
            api(libs.compose.backhandler)

            api(libs.compottie)
            api(libs.compottie.resources)
            api(libs.compottie.dot)
            api(libs.compottie.lite)
            api(libs.compottie.network)
        }
    }
}