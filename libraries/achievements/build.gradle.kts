plugins {
    id("cards.kotlin.multiplatform")
}

// Pure, dependency-light progression module shared by the client
// (libraries:cards:impl) and the server (apps:server): the per-hand fact model
// and the deterministic counter fold that derives achievement progress. Mirrors
// libraries:gameplay — no `android { ... }` block so the server-only Docker
// build (which doesn't apply AGP) can compile it, and a narrow jvm() target so
// apps:server can run the fold on the backend.
kotlin {
    jvm {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            implementation(projects.libraries.core)
        }
    }
}
