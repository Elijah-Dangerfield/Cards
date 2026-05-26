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
