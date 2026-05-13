plugins {
    id("cards.kotlin.multiplatform")
}

android {
    namespace = "com.dangerfield.cards.libraries.storage.impl"
}

moduleConfig.storage()

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.libraries.storage)

            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.cards.storage)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

tasks.matching { it.name.contains("kspCommonMainKotlinMetadata", ignoreCase = true) }
    .configureEach { enabled = false }