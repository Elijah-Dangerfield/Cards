plugins {
    id("cards.feature")
}

android {
    namespace = "com.dangerfield.cards.features.upgrade"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.libraries.config)
        }
    }
}
