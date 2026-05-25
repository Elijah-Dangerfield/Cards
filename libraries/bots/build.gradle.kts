plugins {
    id("cards.kotlin.multiplatform")
}

android {
    namespace = "com.dangerfield.cards.libraries.bots"
}

kotlin {
    // Server consumption: apps:server depends on this module so the bot
    // driver (Phase 3) can call BotDecision on the backend. The convention
    // plugin only sets up android + ios — add jvm() narrowly.
    jvm()
    sourceSets {
        commonMain.dependencies {
            api(projects.libraries.gameplay)
            implementation(projects.libraries.core)
        }
    }
}
