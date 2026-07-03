plugins {
    id("cards.kotlin.multiplatform")
    alias(libs.plugins.kotlinSerialization)
}

// AGP namespace is defaulted from the project path by the KMP convention
// plugin (= `com.dangerfield.cards.libraries.core`). No `android { ... }`
// block here on purpose: the server-only Docker build doesn't apply AGP,
// and a top-level `android { ... }` would fail script compilation there.

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
            // Auth vocabulary enums (AuthRequirement / AuthReason) are @Serializable
            // so navigation can register them as route-arg NavTypes on iOS/Native,
            // which has no built-in enum NavType support.
            implementation(libs.kotlinx.serialization.json)
        }
    }
}