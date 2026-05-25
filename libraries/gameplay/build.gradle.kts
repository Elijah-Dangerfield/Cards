plugins {
    id("cards.kotlin.multiplatform")
}

android {
    namespace = "com.dangerfield.cards.libraries.gameplay"
}

kotlin {
    // Server consumption: apps:server depends on this module to run the
    // GameEngine / GameState / PlayerIntent / GameEvent types on the
    // backend. The convention plugin only sets up android + ios — add jvm()
    // narrowly so we don't impose a JVM target on every KMP library.
    jvm()
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            implementation(projects.libraries.core)
        }
    }
}
