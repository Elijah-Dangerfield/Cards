plugins {
    id("cards.kotlin.multiplatform")
}

moduleConfig {
    di()
    serialization()
    optIn("kotlin.time.ExperimentalTime")
}

android {
    namespace = "com.dangerfield.cards.libraries.identity.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.libraries.identity)
            implementation(projects.libraries.networking)
            implementation(projects.libraries.storage)
            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)
            // For AppEvent + AppEventBus — needed to dispatch SignedOut.
            implementation(projects.libraries.cards)

            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.kotlinx.json)

            // supabase-kt for sign-in (anonymous V1; Apple/Google in Phase 3.1)
            // and session storage + token refresh. The matching Ktor engine
            // is contributed by the consuming app module (ktor-client-okhttp
            // on Android, ktor-client-darwin on iOS).
            //
            // `api` (not `implementation`): the merged anvil component in
            // `:apps:compose` references SupabaseClient via our @ContributesTo
            // interface, so the type has to be on the consumer's classpath.
            api(libs.supabase.auth)
        }
        commonTest.dependencies {
            implementation(projects.libraries.flowroutines.testing)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.networking)
            // ProfileCache depends on storage's Cache / CacheFactory;
            // AppEventBus is in :libraries:cards (needed by the impl ctor).
            // :libraries:core for AutoInit (ProfileRepositoryImpl's
            // supertype — the test compiler has to load it to type-
            // check references to the impl class).
            implementation(projects.libraries.storage)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.core)
            implementation(libs.ktor.client.contentNegotiation)
            // MockEngine is the easiest way to synthesize a real Ktor
            // ClientRequestException with a given HTTP status — the impl's
            // outcome mapping branches on status codes, so we need real
            // exception shapes (not hand-rolled ones).
            implementation("io.ktor:ktor-client-mock:3.3.3")
        }
        androidMain.dependencies {
            // EncryptedSharedPreferences for the OS-encrypted Supabase
            // session store (AUTH-16). iOS uses a Keychain Swift twin.
            implementation(libs.androidx.security.crypto)
        }
    }
}
