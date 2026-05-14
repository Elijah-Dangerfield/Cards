enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "Cards"

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        google()
        mavenCentral()
    }
}

// Apps
include(":apps")
include(":apps:compose")
include(":apps:server")
// Note: iOS app is not a Gradle module - it's an Xcode project in apps/ios/
// Note: Desktop app module exists but may need to be configured

// Features
include(":features:home")
include(":features:home:impl")
include(":features:profile")
include(":features:profile:impl")
include(":features:progression")
include(":features:progression:impl")
include(":features:room")
include(":features:room:impl")
include(":features:shop")
include(":features:shop:impl")
include(":features:upgrade")
include(":features:upgrade:impl")


// Libraries
include(":libraries:config")
include(":libraries:config:impl")
include(":libraries:core")
include(":libraries:bots")
include(":libraries:flowroutines")
include(":libraries:gameplay")
include(":libraries:navigation")
include(":libraries:navigation:impl")
include(":libraries:networking")
include(":libraries:networking:impl")
include(":libraries:resources")
include(":libraries:storage")
include(":libraries:storage:impl")
include(":libraries:cards")
include(":libraries:cards:impl")
include(":libraries:cards:storage")
include(":libraries:ui")