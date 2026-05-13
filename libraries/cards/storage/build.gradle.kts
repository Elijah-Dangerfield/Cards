plugins {
    id("cards.kotlin.multiplatform")
}

android {
    namespace = "com.dangerfield.cards.libraries.cards.storage"
}

moduleConfig.storage()


kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.libraries.cards)
            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.storage)
        }
    }
}