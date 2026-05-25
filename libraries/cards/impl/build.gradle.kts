plugins {
    id("cards.kotlin.multiplatform")
    alias(libs.plugins.sentryKmp)
}

moduleConfig {
    optIn("kotlin.time.ExperimentalTime")
    optIn("kotlin.uuid.ExperimentalUuidApi")
    optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
}

android {
    namespace = "com.dangerfield.cards.libraries.cards.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.libraries.cards)

            implementation(projects.libraries.core)
            implementation(libs.kermit)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.cards.storage)
            implementation(projects.libraries.networking)
            implementation(projects.libraries.identity)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
        }

        commonTest.dependencies {
            implementation(projects.libraries.cards)
            implementation(projects.libraries.cards.storage)
            implementation(projects.libraries.networking)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.flowroutines.testing)
            // :libraries:core for AutoInit (AppEventDispatcher's
            // supertype — the test compiler has to load it to type-
            // check references to the impl class).
            implementation(projects.libraries.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation("io.ktor:ktor-client-mock:3.3.3")
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
