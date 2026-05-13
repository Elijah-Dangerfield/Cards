plugins {
    id("cards.feature")
}

android {
    namespace = "com.dangerfield.cards.features.room"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.libraries.gameplay)
        }
    }
}
