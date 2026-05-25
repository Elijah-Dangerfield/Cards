plugins {
    id("cards.kotlin.multiplatform")
}

android {
    namespace = "com.dangerfield.cards.libraries.core"
}

kotlin {
    // The Ktor server (apps:server) consumes :libraries:core transitively
    // through :libraries:gameplay and :libraries:bots. The convention plugin
    // sets up android + ios; we add jvm() here narrowly so we don't pull
    // every KMP library into a JVM build just to feed the server.
    jvm()
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            implementation(libs.kotlin.inject.runtime.kmp)
        }
    }
}