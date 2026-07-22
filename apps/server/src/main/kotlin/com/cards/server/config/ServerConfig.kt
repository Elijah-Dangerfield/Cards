package com.dangerfield.cards.server.config

/**
 * Root container for everything the server reads from the environment.
 *
 * Parsed once at startup from [Env] and then passed around as plain values.
 * Code that depends on config takes the specific sub-config it needs, not
 * the whole tree — keeps test wiring honest.
 */
data class ServerConfig(
    val database: DatabaseConfig,
    val supabase: SupabaseConfig,
    val http: HttpConfig,
    val sentry: SentryConfig,
    val admin: AdminConfig,
    val accessControl: AccessControlConfig,
    val observability: ObservabilityConfig,
    val configChange: ConfigChangeConfig,
    val billing: BillingConfig,
) {
    companion object {
        fun fromEnv(env: Env = Env()): ServerConfig = ServerConfig(
            database = DatabaseConfig.fromEnv(env),
            supabase = SupabaseConfig.fromEnv(env),
            http = HttpConfig.fromEnv(env),
            sentry = SentryConfig.fromEnv(env),
            admin = AdminConfig.fromEnv(env),
            accessControl = AccessControlConfig.fromEnv(env),
            observability = ObservabilityConfig.fromEnv(env),
            configChange = ConfigChangeConfig.fromEnv(env),
            billing = BillingConfig.fromEnv(env),
        )
    }
}

/**
 * Credentials the real receipt validators (BILL-2) need to verify a paid
 * purchase with each platform store. Every field is optional: a validator
 * whose credentials are unset stays **dormant** and refuses validation, so
 * an unconfigured server can never accept a forged receipt. The
 * [com.dangerfield.cards.server.data.DevReceiptValidator] only runs while
 * the BILL-5 `billing.realPurchasesEnabled` flag keeps the redeem path dark;
 * once that flips on, an unconfigured store simply rejects.
 *
 * Apple ([appleBundleId] + [appleEnvironment]) is enough to verify a
 * StoreKit 2 signed-transaction JWS offline against the bundled Apple root
 * CAs — no App Store Connect API key is required for transaction
 * verification. [appleAppAppleId] is only needed for production online
 * checks and may stay null in sandbox.
 *
 * Google ([googlePackageName] + [googleServiceAccountJson]) calls the Play
 * Developer API's `purchases.products.get`, which requires a service-account
 * key JSON with the Android Publisher scope. The JSON is read verbatim from
 * the env so it can be injected as a Fly secret without a file mount.
 */
data class BillingConfig(
    val appleBundleId: String?,
    val appleEnvironment: String,
    val appleAppAppleId: Long?,
    val googlePackageName: String?,
    val googleServiceAccountJson: String?,
) {
    companion object {
        fun fromEnv(env: Env): BillingConfig = BillingConfig(
            appleBundleId = env["APPLE_BUNDLE_ID"],
            // StoreKit JWS verification needs the target environment; default
            // to Sandbox so a misconfigured prod env fails closed rather than
            // trusting a sandbox receipt against production rules.
            appleEnvironment = env["APPLE_STORE_ENVIRONMENT"] ?: "Sandbox",
            appleAppAppleId = env.get("APPLE_APP_APPLE_ID")?.toLongOrNull(),
            googlePackageName = env["GOOGLE_PLAY_PACKAGE_NAME"],
            googleServiceAccountJson = env["GOOGLE_PLAY_SERVICE_ACCOUNT_JSON"],
        )
    }
}

/**
 * Where to announce config changes. [webhookUrl] is a Slack-compatible incoming
 * webhook; null/blank disables notifications (the default). Set
 * `CONFIG_CHANGE_WEBHOOK_URL` to turn it on.
 */
data class ConfigChangeConfig(
    val webhookUrl: String?,
) {
    companion object {
        fun fromEnv(env: Env): ConfigChangeConfig = ConfigChangeConfig(
            webhookUrl = env["CONFIG_CHANGE_WEBHOOK_URL"]?.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * Settings for the account-access gate that blocks banned users on every
 * authenticated route.
 *
 * [appealUrl] is handed to a blocked client verbatim in the `403` body so the
 * app can offer an "appeal this" link. Null when unset — the client then shows
 * the block without an appeal affordance, which is fine for V1 (the gate still
 * works, the user just has no in-app appeal path). Set `APPEAL_URL` once a
 * support/appeal page exists.
 */
data class AccessControlConfig(
    val appealUrl: String?,
) {
    companion object {
        fun fromEnv(env: Env): AccessControlConfig = AccessControlConfig(
            appealUrl = env["APPEAL_URL"]?.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * Connection settings for the Postgres pool. We accept a single
 * `DATABASE_URL` (12-factor) and parse out user + password from it so
 * Hikari can take them as separate fields — JDBC URLs with passwords
 * embedded are rejected by some drivers / pool configs.
 */
data class DatabaseConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val poolMaxSize: Int,
    val poolMinIdle: Int,
) {
    companion object {
        fun fromEnv(env: Env): DatabaseConfig {
            val raw = env.require("DATABASE_URL")
            val parsed = ParsedPostgresUrl.parse(raw)
            return DatabaseConfig(
                jdbcUrl = parsed.jdbcUrl,
                username = parsed.username,
                password = parsed.password,
                poolMaxSize = env.int("DATABASE_POOL_MAX_SIZE", default = 10),
                poolMinIdle = env.int("DATABASE_POOL_MIN_IDLE", default = 2),
            )
        }
    }
}

/**
 * Settings for verifying Supabase-issued JWTs and calling Supabase's
 * Admin API.
 *
 * Supabase signs JWTs with **ES256** (asymmetric) since their move to
 * "JWT Signing Keys." Our server fetches the project's public key from
 * the JWKS endpoint at `<projectUrl>/auth/v1/.well-known/jwks.json` and
 * verifies signatures against it. No shared secret lives on the server.
 *
 * The plugin caches keys (10 keys for 24h) and rate-limits fetches; key
 * rotations on Supabase's side propagate within the cache TTL.
 *
 * Legacy HS256 + shared secret is deprecated by Supabase and intentionally
 * not supported here — see `docs/decisions.md`.
 *
 * [serviceRoleKey] is the project's service_role JWT, required for
 * Admin API calls (e.g. `DELETE /auth/v1/admin/users/<id>` for account
 * deletion). Optional because most routes don't need it — endpoints
 * that do (see [DELETE /v1/me]) respond with 503 NotConfigured when it
 * isn't set, so non-deletion deployments stay functional. Treat as a
 * root password: never log it, never return it from any endpoint.
 */
data class SupabaseConfig(
    /** e.g. `https://yuqrfhdoejonclgbixlw.supabase.co`. */
    val projectUrl: String,
    val serviceRoleKey: String?,
) {
    /** Issuer the Auth service stamps on every JWT it issues. */
    val expectedIssuer: String get() = "$projectUrl/auth/v1"

    /** Discovery endpoint for the project's public signing keys. */
    val jwksUrl: String get() = "$projectUrl/auth/v1/.well-known/jwks.json"

    companion object {
        fun fromEnv(env: Env): SupabaseConfig {
            val url = env.require("SUPABASE_URL").trimEnd('/')
            return SupabaseConfig(
                projectUrl = url,
                serviceRoleKey = env["SUPABASE_SERVICE_ROLE_KEY"],
            )
        }
    }
}

data class HttpConfig(
    val host: String,
    val port: Int,
) {
    companion object {
        fun fromEnv(env: Env): HttpConfig = HttpConfig(
            host = env.get("SERVER_HOST") ?: "0.0.0.0",
            port = env.int("SERVER_PORT", default = 8080),
        )
    }
}

/**
 * Token-gated admin endpoints (currently only the anon-sweep). Separate
 * env var instead of reusing SUPABASE_SERVICE_ROLE_KEY so an external
 * scheduler can trigger sweeps without holding Supabase admin power.
 *
 * V1 cadence is "call the route on a cron." A future in-process scheduler
 * could read [orphanAnonTtlDays] directly without going through HTTP.
 */
data class AdminConfig(
    val apiToken: String?,
    val orphanAnonTtlDays: Int,
    /**
     * Idle threshold for the room sweep (`POST /v1/admin/sweep-rooms`). A
     * disconnected seat older than this is reaped, and a persisted room with no
     * in-memory owner older than this is deleted from the durable store. Hours,
     * not days: rooms are ephemeral, and live rooms are excluded by the
     * in-memory check, so the only thing this bounds is how long an abandoned
     * (process-stranded) room lingers in Postgres before cleanup.
     */
    val staleRoomTtlHours: Int,
    /**
     * Directory holding the prebuilt admin console bundle served at `/admin`.
     * The default matches the repo layout for local `:apps:server:run`
     * (workingDir = rootDir); the Docker image overrides it. Missing dir →
     * `/admin` simply isn't served.
     */
    val webDir: String = "apps/server/admin-web",
) {
    companion object {
        fun fromEnv(env: Env): AdminConfig = AdminConfig(
            apiToken = env["ADMIN_API_TOKEN"],
            orphanAnonTtlDays = env.int("ORPHAN_ANON_TTL_DAYS", default = 30),
            staleRoomTtlHours = env.int("STALE_ROOM_TTL_HOURS", default = 6),
            webDir = env["ADMIN_WEB_DIR"] ?: "apps/server/admin-web",
        )
    }
}

/**
 * Server-side Sentry settings. The SDK is initialised at startup ONLY when
 * [dsn] is non-blank — V1 ships unconfigured by default so deploys without
 * a Sentry project (or local dev) don't pay any cost.
 *
 * Recommended setup once Sentry exists: one Sentry project for the server,
 * separate from the client project (different SDKs, different stack
 * traces, different release cadence). Within that one project, use
 * `SENTRY_ENVIRONMENT=dev|prod` to filter — distinct *projects* per
 * environment makes cross-env grouping (the same exception happening in
 * both) awkward.
 */
data class SentryConfig(
    /** Project DSN. Sentry → Project Settings → Client Keys. */
    val dsn: String?,
    /** Tagged on every event. Conventional values: `dev`, `prod`. */
    val environment: String,
    /** Optional release identifier. Conventionally the git short SHA. */
    val release: String?,
) {
    companion object {
        fun fromEnv(env: Env): SentryConfig = SentryConfig(
            dsn = env["SENTRY_DSN"],
            // Fly sets FLY_APP_NAME; we map cards-server-prod → prod, anything
            // else (cards-server-dev, local) → dev. Override with
            // SENTRY_ENVIRONMENT when needed.
            environment = env["SENTRY_ENVIRONMENT"]
                ?: when (env["FLY_APP_NAME"]) {
                    "cards-server-prod" -> "prod"
                    else -> "dev"
                },
            release = env["SENTRY_RELEASE"],
        )
    }
}

/**
 * OpenTelemetry exporter wiring. The SDK initialises at boot regardless;
 * the only knob is *where* spans (and logs, once the appender bridge
 * lands) ship to.
 *
 *  - [otlpEndpoint] null/blank → stdout exporter. Spans appear in
 *    `flyctl logs` so dev runs and undeployed environments stay
 *    debuggable without an external sink.
 *  - [otlpEndpoint] set → OTLP/HTTP exporter pointed at the URL.
 *    Standard env name `OTEL_EXPORTER_OTLP_ENDPOINT` so SDK-native
 *    tooling (the CLI, Grafana Cloud's onboarding flow) drops in
 *    without renaming.
 *  - [otlpHeaders] mirrors `OTEL_EXPORTER_OTLP_HEADERS` (comma-separated
 *    `Key=Value` pairs). Grafana Cloud uses one Authorization header
 *    holding `Basic <base64(instance_id:api_token)>`.
 *
 * [serviceName] and [environment] feed the OTel `Resource` so every span
 * carries `service.name` + `deployment.environment` attributes for
 * grouping in the backend. Environment defaults to the Sentry
 * convention (Fly app name → `prod` else `dev`) so traces and errors
 * filter on the same value.
 */
data class ObservabilityConfig(
    val otlpEndpoint: String?,
    val otlpHeaders: String?,
    val serviceName: String,
    val environment: String,
    val release: String?,
) {
    companion object {
        fun fromEnv(env: Env): ObservabilityConfig = ObservabilityConfig(
            otlpEndpoint = env["OTEL_EXPORTER_OTLP_ENDPOINT"],
            otlpHeaders = env["OTEL_EXPORTER_OTLP_HEADERS"],
            serviceName = env["OTEL_SERVICE_NAME"] ?: "cards-server",
            // Match Sentry's environment derivation so a single env tag
            // groups traces + errors consistently.
            environment = env["OTEL_DEPLOYMENT_ENVIRONMENT"]
                ?: env["SENTRY_ENVIRONMENT"]
                ?: when (env["FLY_APP_NAME"]) {
                    "cards-server-prod" -> "prod"
                    else -> "dev"
                },
            release = env["OTEL_SERVICE_VERSION"] ?: env["SENTRY_RELEASE"],
        )
    }
}

/**
 * `postgresql://user:pass@host:port/db?params` →
 *   - jdbcUrl: `jdbc:postgresql://host:port/db?params` (no credentials)
 *   - username, password: extracted (URL-decoded)
 *
 * Internal helper, only made public for tests.
 */
internal data class ParsedPostgresUrl(
    val jdbcUrl: String,
    val username: String,
    val password: String,
) {
    companion object {
        fun parse(raw: String): ParsedPostgresUrl {
            require(raw.startsWith("postgresql://") || raw.startsWith("postgres://")) {
                "DATABASE_URL must start with postgresql:// (got: ${raw.take(20)}…)"
            }
            // Strip scheme to make parsing easier.
            val noScheme = raw.substringAfter("://")
            val atIndex = noScheme.lastIndexOf('@')
            require(atIndex > 0) { "DATABASE_URL must include `user:password@host` credentials" }
            val credsPart = noScheme.substring(0, atIndex)
            val hostPart = noScheme.substring(atIndex + 1)
            val colon = credsPart.indexOf(':')
            require(colon > 0) { "DATABASE_URL credentials must be `user:password`" }
            val user = urlDecode(credsPart.substring(0, colon))
            val pass = urlDecode(credsPart.substring(colon + 1))
            return ParsedPostgresUrl(
                jdbcUrl = "jdbc:postgresql://$hostPart",
                username = user,
                password = pass,
            )
        }

        private fun urlDecode(s: String): String =
            java.net.URLDecoder.decode(s, Charsets.UTF_8)
    }
}
