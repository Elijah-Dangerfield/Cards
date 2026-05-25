plugins {
    id("cards.kotlin.multiplatform")
}

moduleConfig {
    di()
    serialization()
}

android {
    namespace = "com.dangerfield.cards.libraries.products.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.libraries.products)
            implementation(projects.libraries.networking)
            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.billing)
            // SessionTracker (session-rollover trigger) + CacheFactory
            // (on-disk catalog snapshot).
            implementation(projects.libraries.cards)
            implementation(projects.libraries.storage)

            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
        }
        commonTest.dependencies {
            implementation(projects.libraries.flowroutines.testing)
            implementation(libs.ktor.client.contentNegotiation)
            // Tests reference these types directly via fakes that satisfy
            // their interfaces — make them available on the test classpath.
            // :libraries:core for AutoInit (ProductsRepositoryImpl's
            // supertype — the test compiler has to load it to type-
            // check references to the impl class).
            implementation(projects.libraries.products)
            implementation(projects.libraries.networking)
            implementation(projects.libraries.billing)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.storage)
            implementation(projects.libraries.core)
        }
    }
}
