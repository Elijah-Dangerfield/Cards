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

// `-Dcards.serverOnly=true` (set by apps/server/Dockerfile) trims the build
// graph to just what :apps:server needs: itself plus the three shared
// :libraries it depends on (`core`, `gameplay`, `bots`). Everything else
// — features, client-only libraries, the Compose app — is excluded so the
// Docker build context can omit those directories entirely. If apps/server
// picks up a new :libraries:* dep, the Docker build fails at link time with
// "project not found" — a deliberate flag to add the new dir here and in
// the Dockerfile's COPY list. See apps/server/DEPLOY.md → "Server build
// slimming".
val serverOnly = System.getProperty("cards.serverOnly") == "true"

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
include(":apps:server")
// Note: iOS app is not a Gradle module - it's an Xcode project in apps/ios/

// Shared libraries the server depends on (always included).
include(":libraries:core")
include(":libraries:gameplay")
include(":libraries:bots")

if (!serverOnly) {
    include(":apps:compose")
    // End-to-end multiplayer test harness: real client (+ real VMs) driven
    // against a real in-process Ktor server. Depends on client feature/impl
    // modules + :apps:server, so it's gated out of the server-only build.
    include(":apps:integration")

    // Features
    include(":features:home")
    include(":features:home:impl")
    include(":features:lobby")
    include(":features:lobby:impl")
    include(":features:onboarding")
    include(":features:onboarding:impl")
    include(":features:profile")
    include(":features:profile:impl")
    include(":features:progression")
    include(":features:progression:impl")
    include(":features:room")
    include(":features:room:impl")
    include(":features:rooms")
    include(":features:rooms:impl")
    include(":features:shop")
    include(":features:shop:impl")
    include(":features:upgrade")
    include(":features:upgrade:impl")

    // Client-only libraries
    include(":libraries:billing")
    include(":libraries:billing:impl")
    include(":libraries:config")
    include(":libraries:config:impl")
    include(":libraries:flowroutines")
    include(":libraries:flowroutines:testing")
    include(":libraries:game")
    include(":libraries:navigation")
    include(":libraries:navigation:impl")
    include(":libraries:networking")
    include(":libraries:networking:impl")
    include(":libraries:resources")
    include(":libraries:products")
    include(":libraries:products:impl")
    include(":libraries:storage")
    include(":libraries:storage:impl")
    include(":libraries:cards")
    include(":libraries:cards:impl")
    include(":libraries:cards:storage")
    include(":libraries:identity")
    include(":libraries:identity:impl")
    include(":libraries:rooms")
    include(":libraries:rooms:impl")
    include(":libraries:review")
    include(":libraries:review:impl")
    include(":libraries:ui")
}