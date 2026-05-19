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

            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
        }
        commonTest.dependencies {
            implementation(projects.libraries.flowroutines.testing)
            implementation(libs.ktor.client.contentNegotiation)
            // Tests reference these types directly via fakes that satisfy
            // their interfaces — make them available on the test classpath.
            implementation(projects.libraries.products)
            implementation(projects.libraries.networking)
            implementation(projects.libraries.billing)
        }
    }
}
