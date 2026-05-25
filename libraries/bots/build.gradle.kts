plugins {
    id("cards.kotlin.multiplatform")
}

android {
    namespace = "com.dangerfield.cards.libraries.bots"
}

kotlin {
    // Server consumption: apps:server depends on this module so the bot
    // driver (Phase 3) can call BotDecision on the backend. The convention
    // plugin only sets up android + ios — add jvm() narrowly. Pin the JVM
    // target's bytecode to 17 to match apps:server (see core for rationale).
    jvm {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.libraries.gameplay)
            implementation(projects.libraries.core)
        }
    }
}
