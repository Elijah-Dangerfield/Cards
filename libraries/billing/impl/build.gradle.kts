plugins {
    id("cards.kotlin.multiplatform")
}

moduleConfig {
    di()
}

android {
    namespace = "com.dangerfield.cards.libraries.billing.impl"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.libraries.billing)
            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)
            // PurchaseChipPackUseCase wires the platform store to the wallet
            // (chips) + auth (anonymous gating) + catalog (Product.ChipPack).
            implementation(projects.libraries.cards)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.products)
        }
        commonTest.dependencies {
            implementation(projects.libraries.flowroutines.testing)
            implementation(projects.libraries.billing)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.products)
        }
    }
}
