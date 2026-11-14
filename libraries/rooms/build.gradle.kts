plugins {
    id("cards.kotlin.multiplatform")
}

moduleConfig {
    serialization()
    di()
    optIn("kotlin.time.ExperimentalTime")
}

android {
    namespace = "com.dangerfield.cards.libraries.rooms"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            api(projects.libraries.core)
            api(projects.libraries.config)
            // ClientFrame / GameplayFrame carry PlayerIntent, GameState,
            // and GameEvent in their public surface — the api expresses
            // gameplay, so it depends on the gameplay model.
            api(projects.libraries.gameplay)
        }
    }
}
