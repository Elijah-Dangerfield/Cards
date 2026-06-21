plugins {
    id("cards.kotlin.multiplatform")
}

moduleConfig {
    serialization()
}

android {
    namespace = "com.dangerfield.cards.features.rooms"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.libraries.navigation)
        }
    }
}
