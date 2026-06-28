plugins {
    id("cards.kotlin.multiplatform")
}

moduleConfig {
    serialization()
}

android {
    namespace = "com.dangerfield.cards.libraries.billing"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.libraries.core)
            // Product.ChipPack is the input to PurchaseChipPackUseCase.
            implementation(projects.libraries.products)
            // The billing ConfiguredValue flag (RealPurchasesEnabled) lives
            // alongside the billing api so the use case + any consumer sees it
            // with the billing import.
            implementation(projects.libraries.config)
            api(libs.kotlinx.coroutines.core)
        }
    }
}
