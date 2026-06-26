plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    application
}

application {
    mainClass.set("com.dangerfield.cards.server.MainKt")
}

tasks.named<JavaExec>("run") {
    // Resolve env paths (apps/server/.env) from the repo root so `./gradlew :apps:server:run`
    // finds the local dev secrets instead of the subproject dir.
    workingDir = rootDir
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    // All Ktor server modules track the catalog `ktor` version. Earlier
    // these were hardcoded to 3.3.3, which silently kept loading
    // 3.3.x-compiled bytecode (e.g. ratelimit's `Route.getParent()` call)
    // after the catalog bumped to 3.5.0 — symptom was NoSuchMethodError
    // at runtime in WalletRoutesTest.
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serverCallId)
    implementation(libs.ktor.serverRateLimit)
    // WebSockets for the per-room presence channel + future server-
    // authoritative gameplay sync. Ktor's built-in ping/timeout handles
    // dead-peer detection without us hand-rolling a heartbeat.
    implementation(libs.ktor.serverWebsockets)

    // Error reporting. SDK initialises only when SENTRY_DSN is set,
    // so the dependency is paid (in jar size) but stays a no-op for
    // unconfigured deploys. See plugins/Sentry.kt.
    implementation("io.sentry:sentry:7.18.1")

    // OpenTelemetry traces — SDK initialises at boot. When the OTLP
    // endpoint is unset, falls back to a stdout exporter so spans show
    // in `flyctl logs` without a sink being provisioned yet. See
    // plugins/Telemetry.kt and docs/todo.md §C Observability.
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.sdk.logs)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.opentelemetry.exporter.logging)
    implementation(libs.opentelemetry.exporter.logging.otlp)
    implementation(libs.opentelemetry.logback.appender)
    // Auto-instruments the Ktor server: one span per HTTP request, so
    // ordinary traffic produces traces without manual withSpan calls.
    implementation(libs.opentelemetry.ktor)
    // Bridges OTel `Context` into Kotlin's `CoroutineContext` so a span
    // started in one suspension shows up as the parent for child spans
    // started across a `withContext(span.asContextElement())` block.
    implementation(libs.opentelemetry.extension.kotlin)

    implementation(libs.ktor.serialization.kotlinx.json)
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)

    // Outbound HTTP — for calling Supabase's Admin API (account deletion,
    // future admin lookups). CIO is engine-of-choice on JVM: pure Kotlin,
    // small footprint, no extra native deps to ship in the Docker image.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.contentNegotiation)
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

    // Shared game code: GameEngine, GameState, PlayerIntent, GameEvent
    // (and the bot decision logic) all live in client-shared KMP libraries.
    // The server runs the engine for real multiplayer poker — same code on
    // both ends so wire-format incompatibilities are impossible. See
    // libraries/{gameplay,bots}/build.gradle.kts for the jvm() target.
    implementation(projects.libraries.gameplay)
    implementation(projects.libraries.bots)
    // Shared per-hand fact model + the achievement-counter fold — one source of
    // truth the server (authority) and client (optimistic display) both run.
    implementation(projects.libraries.achievements)
    // `Catching {}` from :libraries:core — AGENTS.md mandates Catching over
    // runCatching across the repo. Pulled in transitively via gameplay/bots
    // today, but a direct dep is the honest declaration.
    implementation(projects.libraries.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.client.contentNegotiation)
    testImplementation(libs.testcontainers.postgres)
    // OTel SDK test harness: in-memory span exporter for unit tests.
    testImplementation(libs.opentelemetry.sdk.testing)
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
