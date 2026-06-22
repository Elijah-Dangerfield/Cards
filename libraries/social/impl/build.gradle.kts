plugins {
    id("cards.kotlin.multiplatform")
}

moduleConfig {
    di()
    serialization()
    optIn("kotlin.time.ExperimentalTime")
}

android {
    namespace = "com.dangerfield.cards.libraries.social.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.libraries.social)
            implementation(projects.libraries.networking)
            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)

            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(projects.libraries.flowroutines.testing)
            implementation(projects.libraries.social)
            implementation(libs.ktor.client.core)
            // MockEngine synthesizes a real Ktor ClientRequestException with a
            // given status — the friend-request error mapping branches on
            // status codes, so we need the real exception shape.
            implementation("io.ktor:ktor-client-mock:3.3.3")
        }
    }
}
