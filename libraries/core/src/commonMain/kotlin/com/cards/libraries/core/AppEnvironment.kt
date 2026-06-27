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
 * Publishable keys are public by design (Postgres RLS is the real guard, not
 * secrecy), so embedding both here is fine — same reasoning that kept the dev
 * key in source before this consolidation. These replaced the legacy `anon`
 * JWT keys when both projects moved to Supabase's new publishable/secret keys.
 *
 * Named `AppEnvironment` rather than `Environment`: this is a public type exported
 * into the iOS framework, and a bare `Environment` collides with `SwiftUI.Environment`
 * in any Swift file that imports both — breaking `@Environment` property wrappers.
 */
enum class AppEnvironment(
    val displayName: String,
    val baseUrl: String,
    val supabaseProjectRef: String,
    val supabasePublishableKey: String,
) {
    Dev(
        displayName = "dev",
        baseUrl = "https://cards-server-dev.fly.dev",
        supabaseProjectRef = "yuqrfhdoejonclgbixlw",
        supabasePublishableKey = "sb_publishable_DePmCC4oTQiiTtqPVjC-sw_8TRLa7pt",
    ),
    Prod(
        displayName = "prod",
        baseUrl = "https://cards-server-prod.fly.dev",
        supabaseProjectRef = "kzohlyvmnnvyabspzpbb",
        supabasePublishableKey = "sb_publishable_R9o8bEnWEZG5M_ooAwkujw_SYnZ58el",
    ),
    ;

    /** Derived from the project ref — Supabase URLs are always `https://<ref>.supabase.co`. */
    val supabaseUrl: String get() = "https://$supabaseProjectRef.supabase.co"

    companion object {
        /** The environment this build resolves to. See the class kdoc for the rules. */
        val current: AppEnvironment = when (CardsBuildConfig.TARGET_ENV.lowercase()) {
            "dev" -> Dev
            "prod" -> Prod
            else -> if (BuildInfo.isDebug) Dev else Prod
        }
    }
}
