plugins {
    id("cards.kotlin.multiplatform")
}

moduleConfig {
    serialization()
}

android {
    namespace = "com.dangerfield.cards.libraries.review"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.libraries.core)
            api(libs.kotlinx.coroutines.core)
        }
    }
}
