plugins {
    id("cards.kotlin.multiplatform")
}

moduleConfig {
    optIn("kotlin.time.ExperimentalTime")
}

android {
    namespace = "com.dangerfield.cards.libraries.social"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            // The social ConfiguredValue flag (SocialEnabled) lives alongside the
            // social api so any consumer (Home, Profile, the at-table sheet) sees
            // it with the social import.
            implementation(projects.libraries.config)
        }
    }
}
