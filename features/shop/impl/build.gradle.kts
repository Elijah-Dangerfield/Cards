plugins {
    id("cards.feature")
}

android {
    namespace = "com.dangerfield.cards.features.shop.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.shop)
            // FeedbackRoute lives in :features:profile (the routes-only
            // module). Used by the shop's "Got an idea?" footer.
            implementation(projects.features.profile)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.products)
            implementation(projects.libraries.billing)
            implementation(projects.libraries.identity)
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
            implementation(projects.libraries.flowroutines.testing)
            implementation(projects.libraries.products)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.billing)
            implementation(projects.libraries.identity)
        }
    }
}
