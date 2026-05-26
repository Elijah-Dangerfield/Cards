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
    //
    // Pin the JVM target's bytecode to JVM 17 so the server's Java 17
    // runtime can load these classes (without this, the JVM target picks
    // up the machine default JDK — Java 21 on CI/dev boxes — and the
    // server fails with UnsupportedClassVersionError). Pin only the jvm()
    // target, not the whole module: setting `jvmToolchain` here would
    // also lower Kotlin's Android compile target and clash with AGP's
    // Java 21 default for Android, breaking the Android build.
    jvm {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            implementation(libs.kotlin.inject.runtime.kmp)
        }
    }
}