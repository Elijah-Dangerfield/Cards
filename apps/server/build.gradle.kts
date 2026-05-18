plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    application
}

application {
    mainClass.set("com.dangerfield.cards.server.MainKt")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation("io.ktor:ktor-server-content-negotiation:3.3.3")
    implementation("io.ktor:ktor-server-cors:3.3.3")
    implementation("io.ktor:ktor-server-status-pages:3.3.3")
    implementation("io.ktor:ktor-server-call-logging:3.3.3")
    implementation("io.ktor:ktor-server-call-id:3.3.3")
    implementation("io.ktor:ktor-server-rate-limit:3.3.3")

    // Error reporting. SDK initialises only when SENTRY_DSN is set,
    // so the dependency is paid (in jar size) but stays a no-op for
    // unconfigured deploys. See plugins/Sentry.kt.
    implementation("io.sentry:sentry:7.18.1")
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)

    // Outbound HTTP — for calling Supabase's Admin API (account deletion,
    // future admin lookups). CIO is engine-of-choice on JVM: pure Kotlin,
    // small footprint, no extra native deps to ship in the Docker image.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.mock)

    // Auth — JWT verification. Auth0's java-jwt is what
    // ktor-server-auth-jwt sits on top of; we use it directly when
    // constructing our verifier. `jwks-rsa` adds the JwkProvider that
    // fetches Supabase's public signing keys from the project's JWKS
    // endpoint, with built-in caching + rate-limiting.
    implementation(libs.auth0.jwt)
    implementation(libs.auth0.jwksRsa)

    // Database — Postgres + HikariCP + Exposed DSL + Flyway migrations.
    // See docs/decisions.md "Server query layer" entry for the rationale.
    implementation(libs.postgres.jdbc)
    implementation(libs.hikaricp)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgres)

    // DI — same pattern as the client: kotlin-inject + anvil
    implementation(libs.kotlin.inject.runtime.kmp)
    implementation(libs.anvil.runtime)
    implementation(libs.anvil.runtime.optional)
    ksp(libs.kotlin.inject.compiler.ksp)
    ksp(libs.anvil.compiler)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation("io.ktor:ktor-client-content-negotiation:3.3.3")
    testImplementation(libs.testcontainers.postgres)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Match the client-side convention plugins' default opt-ins so
        // KSP-generated code that calls stdlib's still-experimental types
        // (Clock, Uuid) compiles cleanly. See
        // build-logic/.../optInKotlinMarkers for the client equivalent.
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.time.ExperimentalTime",
            "-opt-in=kotlin.uuid.ExperimentalUuidApi",
        )
    }
}
