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
            // BillingRepositoryImpl POSTs receipts to /v1/billing/redeem
            // (server-authoritative credit, BILL-5).
            implementation(projects.libraries.config)
            implementation(projects.libraries.networking)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            // PlayBillingClient drives Play Billing for real Android IAP. The
            // foreground Activity (for launchBillingFlow) comes from the
            // host-app ActivityProvider in :libraries:cards (already a
            // commonMain dep above).
            implementation(libs.google.play.billing.ktx)
        }
        commonTest.dependencies {
            implementation(projects.libraries.flowroutines.testing)
            implementation(projects.libraries.billing)
            implementation(projects.libraries.cards)
            implementation(projects.libraries.identity)
            implementation(projects.libraries.products)
            implementation(projects.libraries.config)
        }
    }
}
