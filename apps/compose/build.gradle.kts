plugins {
    id("cards.application")
    id("co.touchlab.skie") version "0.10.13"

}

android {
    namespace = "com.dangerfield.cards"
}

kotlin {

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.androidx.work.runtime)
            implementation(compose.uiTooling)
        }

        commonMain.dependencies {
            // Project dependencies
            api(projects.libraries.core)
            implementation(projects.libraries.ui)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.cards.impl)
            implementation(projects.libraries.flowroutines)
            implementation(projects.libraries.bots)
            implementation(projects.libraries.gameplay)
            implementation(projects.libraries.navigation)
            implementation(projects.libraries.navigation.impl)
            implementation(projects.libraries.resources)

            implementation(projects.libraries.storage)
            implementation(projects.libraries.storage.impl)
            implementation(projects.libraries.cards.storage)
            implementation(projects.libraries.config)
            implementation(projects.libraries.config.impl)
            implementation(projects.libraries.cards.storage)
            implementation(projects.libraries.networking)
            implementation(projects.libraries.networking.impl)
            implementation(projects.libraries.products)
            implementation(projects.libraries.products.impl)
            implementation(projects.libraries.billing)
            implementation(projects.libraries.billing.impl)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.identity.impl)
            implementation(projects.libraries.social)
            implementation(projects.libraries.social.impl)
            implementation(projects.libraries.rooms)
            implementation(projects.libraries.rooms.impl)
            implementation(projects.libraries.review)
            implementation(projects.libraries.review.impl)
            implementation(projects.libraries.telemetry.impl)

            implementation(projects.features.home)
            implementation(projects.features.home.impl)
            implementation(projects.features.lobby)
            implementation(projects.features.lobby.impl)
            implementation(projects.features.onboarding)
            implementation(projects.features.onboarding.impl)
            implementation(projects.features.profile)
            implementation(projects.features.profile.impl)
            implementation(projects.features.progression)
            implementation(projects.features.progression.impl)
            implementation(projects.features.room)
            implementation(projects.features.room.impl)
            implementation(projects.features.rooms)
            implementation(projects.features.rooms.impl)
            implementation(projects.features.shop)
            implementation(projects.features.shop.impl)
            implementation(projects.features.upgrade)
            implementation(projects.features.upgrade.impl)

            implementation(libs.atomicfu)
            
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
        }
    }
}