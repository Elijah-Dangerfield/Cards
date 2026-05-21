plugins {
    id("cards.kotlin.multiplatform")
}

moduleConfig {
    di()
}

android {
    namespace = "com.dangerfield.cards.libraries.review.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.libraries.review)
            implementation(projects.libraries.core)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.storage)
        }
        commonTest.dependencies {
            implementation(projects.libraries.flowroutines.testing)
            implementation(projects.libraries.review)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.storage)
        }
    }
}
