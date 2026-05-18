plugins {
    id("cards.kotlin.multiplatform")
}

android {
    namespace = "com.dangerfield.cards.libraries.game"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.libraries.gameplay)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            implementation(projects.libraries.core)
        }
    }
}
