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
    }
}
