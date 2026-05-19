plugins {
    id("cards.kotlin.multiplatform")
}

moduleConfig {
    di()
}

android {
    namespace = "com.dangerfield.cards.libraries.billing.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.libraries.billing)
            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)
        }
        commonTest.dependencies {
            implementation(projects.libraries.flowroutines.testing)
            implementation(projects.libraries.billing)
        }
    }
}
