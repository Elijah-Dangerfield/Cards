plugins {
    id("cards.feature")
}

android {
    namespace = "com.dangerfield.cards.features.room.impl"

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
            implementation(projects.features.room)
            implementation(projects.features.home)
            implementation(projects.features.progression)
            implementation(projects.features.shop)
            implementation(projects.features.lobby)
            implementation(projects.features.rooms)
            // ClaimAccountRoute — the MP quick-buy routes anonymous users to the
            // same account-claim flow the shop uses.
            implementation(projects.features.profile)
            implementation(projects.libraries.bots)
            // PurchaseChipPackUseCase + IapPurchaseOutcome for the in-game quick-buy.
            implementation(projects.libraries.billing)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.game)
            implementation(projects.libraries.gameplay)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.core)
            implementation(projects.libraries.config)
            // FriendRepository — the player card's "Add friend" affordance sends a
            // request to a human opponent (the friend graph's recently-played gate).
            implementation(projects.libraries.social)
            // Product catalog — the player-profile sheet resolves a player's
            // equipped badges/titles to display metadata from the catalog.
            implementation(projects.libraries.products)
            implementation(projects.libraries.flowroutines)
            // RemotePokerSession + Factory consume RoomRepository.connect()
            // and the RoomConnectionHandle / GameplayFrame / ClientFrame
            // types it exposes for multiplayer hand playback.
            implementation(projects.libraries.rooms)
            implementation(projects.libraries.resources)
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
            implementation(projects.libraries.billing)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.config)
            implementation(projects.libraries.core)
            implementation(projects.libraries.game)
            implementation(projects.libraries.gameplay)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.products)
            implementation(projects.libraries.resources)
            implementation(projects.libraries.review)
            implementation(projects.libraries.social)
            implementation(projects.libraries.storage)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.ui)
            implementation(projects.libraries.rooms)
        }
        // PlayPokerScreen Compose UI tests (MP-2). Compose-MP's host-side test
        // harness (`runComposeUiTest`) only runs on the Android target via
        // Robolectric, so these live in androidUnitTest rather than commonTest.
        androidUnitTest.dependencies {
            implementation(libs.compose.uiTest)
            // Supplies the ComponentActivity the host-side Compose harness
            // launches; without it Robolectric can't resolve the activity.
            implementation(libs.compose.uiTestManifest)
            implementation(libs.robolectric)
        }
    }
}
