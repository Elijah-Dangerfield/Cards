plugins {
    id("cards.feature")
}

android {
    namespace = "com.dangerfield.cards.features.room.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.room)
            implementation(projects.features.progression)
            implementation(projects.libraries.bots)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.game)
            implementation(projects.libraries.gameplay)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.review)
            implementation(projects.libraries.ui)
            implementation(projects.libraries.navigation)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
        }
        commonTest.dependencies {
            // CoroutineTest base + TestDispatcherProvider + Turbine come via this
            // single dep — see :libraries:flowroutines:testing.
            implementation(projects.libraries.flowroutines.testing)
            // Tests reference types from these libraries directly via fakes that
            // satisfy their interfaces. `implementation` deps from commonMain are
            // visible at compile time, but transitive types from those modules
            // (e.g. `Cache` from libraries/storage, which `AppCache` extends) need
            // to be explicitly available on the test classpath.
            implementation(projects.libraries.bots)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.game)
            implementation(projects.libraries.gameplay)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.review)
            implementation(projects.libraries.storage)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.ui)
        }
    }
}
