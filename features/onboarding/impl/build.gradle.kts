plugins {
    id("cards.feature")
}

android {
    namespace = "com.dangerfield.cards.features.onboarding.impl"

    // Compose UI tests run as JVM unit tests under Robolectric, so they need
    // the merged Android resources (theme attrs, the test-manifest activity) on
    // the unit-test classpath. Without this the host-side Compose test harness
    // can't inflate its ComponentActivity.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.onboarding)
            implementation(projects.features.home)
            implementation(projects.libraries.navigation)

            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.ui)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.config)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.resources)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
        }
        commonTest.dependencies {
            implementation(projects.libraries.flowroutines.testing)
            implementation(projects.features.onboarding)
            implementation(projects.libraries.core)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.config)
            implementation(libs.turbine)
        }
        // OnboardingScreen Compose UI tests. Compose-MP's host-side test
        // harness (`runComposeUiTest`) only runs on the Android target via
        // Robolectric, so these live in androidUnitTest rather than commonTest.
        androidUnitTest.dependencies {
            implementation(projects.libraries.ui)
            implementation(libs.compose.uiTest)
            // Supplies the ComponentActivity the host-side Compose harness
            // launches; without it Robolectric can't resolve the activity.
            implementation(libs.compose.uiTestManifest)
            implementation(libs.robolectric)
        }
    }
}
