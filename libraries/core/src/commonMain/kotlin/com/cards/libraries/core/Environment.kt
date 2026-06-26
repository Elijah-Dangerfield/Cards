package com.dangerfield.cards.libraries.core

import com.dangerfield.cards.buildinfo.CardsBuildConfig

/**
 * The backend environment a build talks to. There are exactly two — [Dev] and
 * [Prod] — each a complete bundle of the Cards server URL plus the Supabase
 * project that issues and verifies its auth. They're separate databases, so a
 * flag, wallet, or user in one is invisible to the other.
 *
 * [current] picks one. Default: debug builds → [Dev], release builds → [Prod],
 * so a shipped build can never accidentally point at dev. A build-time override
 * (`cards.targetEnv=dev|prod` in local.properties → [CardsBuildConfig.TARGET_ENV])
 * forces a choice for local testing; it's CI-guarded so an override can't land
 * on a shared branch — see `Versioning.kt#loadServerMetadata`.
 *
 * Anon keys are public by design (Postgres RLS is the real guard, not secrecy),
 * so embedding both here is fine — same reasoning that kept the dev key in
 * source before this consolidation.
 */
enum class Environment(
    val displayName: String,
    val baseUrl: String,
    val supabaseProjectRef: String,
    val supabaseAnonKey: String,
) {
    Dev(
        displayName = "dev",
        baseUrl = "https://cards-server-dev.fly.dev",
        supabaseProjectRef = "yuqrfhdoejonclgbixlw",
        supabaseAnonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIs" +
            "InJlZiI6Inl1cXJmaGRvZWpvbmNsZ2JpeGx3Iiwicm9sZSI6ImFub24iLCJ" +
            "pYXQiOjE3NzkxMDY3NDEsImV4cCI6MjA5NDY4Mjc0MX0.0xX2-uFVNic_D" +
            "36BMooHA5m8DEIOns1Y_XCVqECCtwA",
    ),
    Prod(
        displayName = "prod",
        baseUrl = "https://cards-server-prod.fly.dev",
        supabaseProjectRef = "kzohlyvmnnvyabspzpbb",
        // TODO(prod-anon-key): paste the kzohlyvmnnvyabspzpbb project's anon
        // (public) key here. Left empty so a prod-targeted build fails Supabase
        // auth loudly instead of silently hitting the wrong project.
        supabaseAnonKey = "",
    ),
    ;

    /** Derived from the project ref — Supabase URLs are always `https://<ref>.supabase.co`. */
    val supabaseUrl: String get() = "https://$supabaseProjectRef.supabase.co"

    companion object {
        /** The environment this build resolves to. See the class kdoc for the rules. */
        val current: Environment = when (CardsBuildConfig.TARGET_ENV.lowercase()) {
            "dev" -> Dev
            "prod" -> Prod
            else -> if (BuildInfo.isDebug) Dev else Prod
        }
    }
}
