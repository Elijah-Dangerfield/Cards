plugins {
    id("cards.kotlin.multiplatform")
}

// AGP namespace is defaulted from the project path by the KMP convention
// plugin (= `com.dangerfield.cards.libraries.gameplay`). No `android { ... }`
// block here on purpose: the server-only Docker build doesn't apply AGP,
// and a top-level `android { ... }` would fail script compilation there.

kotlin {
    // Server consumption: apps:server depends on this module to run the
    // GameEngine / GameState / PlayerIntent / GameEvent types on the
    // backend. The convention plugin only sets up android + ios — add jvm()
    // narrowly so we don't impose a JVM target on every KMP library.
    // Pin the JVM target's bytecode to 17 to match apps:server (see core
    // for the full rationale on why we scope to the jvm() target instead
    // of the module-wide jvmToolchain).
    jvm {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            implementation(projects.libraries.core)
        }
    }
}
