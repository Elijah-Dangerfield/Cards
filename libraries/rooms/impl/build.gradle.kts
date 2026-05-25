plugins {
    id("cards.kotlin.multiplatform")
}

moduleConfig {
    di()
    serialization()
    optIn("kotlin.time.ExperimentalTime")
    optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
}

android {
    namespace = "com.dangerfield.cards.libraries.rooms.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.libraries.rooms)
            implementation(projects.libraries.networking)
            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)
            // Multiplayer wire frames carry GameState / GameEvent /
            // PlayerIntent. The server runs the same gameplay engine so
            // the wire types match by construction.
            implementation(projects.libraries.gameplay)

            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(projects.libraries.flowroutines.testing)
            implementation(projects.libraries.networking)
            implementation(projects.libraries.rooms)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.turbine)
        }
    }
}
