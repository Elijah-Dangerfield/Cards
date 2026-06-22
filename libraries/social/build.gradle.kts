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
        }
    }
}
