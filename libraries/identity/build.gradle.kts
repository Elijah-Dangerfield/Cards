plugins {
    id("cards.kotlin.multiplatform")
}

moduleConfig {
    serialization()
    optIn("kotlin.time.ExperimentalTime")
}

android {
    namespace = "com.dangerfield.cards.libraries.identity"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            // The identity ConfiguredValue flags (GoogleSignInEnabled, …) live
            // alongside the identity api so any consumer (sign-in screen, claim
            // screen) sees them with the identity import.
            implementation(projects.libraries.config)
        }
    }
}
