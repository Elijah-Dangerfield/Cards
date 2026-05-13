plugins {
    id("cards.kotlin.multiplatform")
}

android {
    namespace = "com.dangerfield.cards.libraries.config"
}


kotlin {
    sourceSets {
        commonMain.dependencies {
            
            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)
            implementation(libs.kotlin.inject.runtime.kmp)
        }
    }
}