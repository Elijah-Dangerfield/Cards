plugins {
    id("cards.feature")
}

android {
    namespace = "com.dangerfield.cards.features.progression"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.libraries.navigation)
        }
    }
}
