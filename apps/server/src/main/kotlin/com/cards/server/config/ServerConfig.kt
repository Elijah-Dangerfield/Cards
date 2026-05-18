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
) {
    companion object {
        fun fromEnv(env: Env = Env()): ServerConfig = ServerConfig(
            database = DatabaseConfig.fromEnv(env),
            supabase = SupabaseConfig.fromEnv(env),
            http = HttpConfig.fromEnv(env),
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
 * Settings for verifying Supabase-issued JWTs.
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
 * We don't store the service-role key here — V1 doesn't call the Supabase
 * Admin API. Add a separate `SUPABASE_SERVICE_ROLE_KEY` field if and when
 * we do (account deletion compliance, admin lookups).
 */
data class SupabaseConfig(
    /** e.g. `https://yuqrfhdoejonclgbixlw.supabase.co`. */
    val projectUrl: String,
) {
    /** Issuer the Auth service stamps on every JWT it issues. */
    val expectedIssuer: String get() = "$projectUrl/auth/v1"

    /** Discovery endpoint for the project's public signing keys. */
    val jwksUrl: String get() = "$projectUrl/auth/v1/.well-known/jwks.json"

    companion object {
        fun fromEnv(env: Env): SupabaseConfig {
            val url = env.require("SUPABASE_URL").trimEnd('/')
            return SupabaseConfig(projectUrl = url)
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
