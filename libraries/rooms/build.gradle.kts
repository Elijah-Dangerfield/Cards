plugins {
    id("cards.kotlin.multiplatform")
}

moduleConfig {
    serialization()
    optIn("kotlin.time.ExperimentalTime")
}

android {
    namespace = "com.dangerfield.cards.libraries.rooms"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
    }
}
