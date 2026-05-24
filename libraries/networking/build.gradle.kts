plugins {
    id("cards.kotlin.multiplatform")
}

moduleConfig {
    // DI is enabled on the api module so the default `NoOpAuthTokenProvider`
    // binding lives here. That makes the class reference-able from any other
    // impl module (which can only depend on api modules per ModuleBoundaries)
    // via `@ContributesBinding(replaces = [NoOpAuthTokenProvider::class])`.
    // Without that path, no auth-feature impl could swap in a real provider
    // because anvil would refuse to see two bindings for AuthTokenProvider.
    di()
}

android {
    namespace = "com.dangerfield.cards.libraries.networking"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.client.core)
            api(libs.kotlinx.serialization.json)

            implementation(projects.libraries.core)
        }
        commonTest.dependencies {
            implementation(projects.libraries.flowroutines.testing)
            implementation(projects.libraries.core)
        }
    }
}
