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
            api(libs.kotlinx.coroutines.core)
        }
    }
}
