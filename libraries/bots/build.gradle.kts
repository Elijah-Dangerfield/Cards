plugins {
    id("cards.kotlin.multiplatform")
}

android {
    namespace = "com.dangerfield.cards.libraries.bots"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.libraries.gameplay)
            implementation(projects.libraries.core)
        }
    }
}
