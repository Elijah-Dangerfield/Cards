plugins {
    id("cards.kotlin.multiplatform")
}

android {
    namespace = "com.dangerfield.cards.apps.integration"
}

// End-to-end integration harness. The tests run as Android unit tests on the
// host JVM (`testDebugUnitTest`) — the same path the feature view models already
// compile through — so they can drive the REAL client stack (and the real
// LobbyViewModel) against a REAL in-process Ktor server. Everything lives in the
// `androidUnitTest` source set; commonMain stays empty (nothing ships here, and
// the iOS target must not try to link the JVM-only server).
//
// Approach C (see docs/testing-plan.md + the plan): no `jvm{}` targets are added
// to the client libraries — we reuse their existing Android variants on the host
// JVM. The one thing this proves out is consuming the JVM-only `:apps:server`
// from an Android unit-test classpath (Milestone 0 spike).
kotlin {
    sourceSets {
        androidUnitTest.dependencies {
            // Real server: routes, InMemoryRoomService, the install* plugin
            // helpers, and the SUPABASE_JWT_AUTH realm name.
            implementation(projects.apps.server)

            // Real client view models + the transport beneath them.
            implementation(projects.features.lobby.impl)
            implementation(projects.features.room.impl)
            implementation(projects.libraries.rooms)
            implementation(projects.libraries.rooms.impl)
            implementation(projects.libraries.networking)
            implementation(projects.libraries.networking.impl)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.core)
            implementation(projects.libraries.gameplay)

            // ConfigManifestDriftTest: the real ConfiguredValue classes + their
            // base types, so the test can check the admin manifest registry
            // against the in-code defaults.
            implementation(projects.libraries.config)
            implementation(projects.libraries.social)
            implementation(projects.features.upgrade)
            implementation(libs.kotlinx.serialization.json)

            // Boot a real server on an ephemeral port + verify HS256 test JWTs.
            implementation(libs.ktor.serverCore)
            implementation(libs.ktor.serverNetty)
            implementation(libs.ktor.serverAuthJwt)
            implementation(libs.auth0.jwt)
            // The client's HttpClient {} declares no engine; supply a JVM one so
            // engine auto-discovery is deterministic on the host JVM.
            implementation(libs.ktor.client.okhttp)

            implementation(libs.kotlin.test)
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}

// ConfigManifestDriftTest reads the manifest registry at runtime, so declare it
// as a test input — otherwise editing only the JSON leaves the test up-to-date
// and Gradle skips the drift check.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("apps/admin/config-manifest-registry.json"))
        .withPropertyName("configManifestRegistry")
}
