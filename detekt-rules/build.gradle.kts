plugins {
    alias(libs.plugins.kotlinJvm)
}

// Custom detekt rules live in their own JVM module: detekt discovers them via
// the ServiceLoader on the `detektPlugins` classpath, so the rules must be a
// standalone jar — they can't live inside `build-logic` (an included build) or
// a multiplatform module. Wired into the build by the `cards.detekt` convention.
//
// No `detekt-test` here yet: dev.detekt:detekt-test:2.0.0-alpha.5 declares a
// runtime dependency on a `detekt-api` test-fixtures jar that isn't published to
// Maven Central for this alpha (404), so it can't resolve. Rule behaviour is
// instead verified by the real `detekt` task running over a sample in CI. Add
// the test dep back once the alpha publishes its fixtures.
kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly(libs.detekt.api)
}
