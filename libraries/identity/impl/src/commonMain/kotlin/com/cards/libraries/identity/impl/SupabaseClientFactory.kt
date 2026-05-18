package com.dangerfield.cards.libraries.identity.impl

import com.dangerfield.cards.libraries.identity.IdentityConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Provides the singleton [SupabaseClient]. Configured with the Auth
 * plugin installed — that's enough for V1 (anonymous sign-in + JWT
 * lifecycle). Add `Postgrest`, `Storage`, etc. installs here when we
 * actually need them.
 *
 * `autoLoadFromStorage` defaults to true, so sessions persist across
 * process restarts using `multiplatform-settings` underneath. We never
 * need to wire our own token store.
 *
 * The Ktor engine is auto-detected (`OkHttp` on Android, `Darwin` on iOS)
 * because we have both engines on the classpath via `:apps:compose`.
 */
@ContributesTo(AppScope::class)
interface SupabaseClientComponent {

    @SingleIn(AppScope::class)
    @Provides
    fun provideSupabaseClient(config: IdentityConfig): SupabaseClient =
        createSupabaseClient(
            supabaseUrl = config.supabaseUrl,
            supabaseKey = config.supabaseAnonKey,
        ) {
            install(Auth) {
                // alwaysAutoRefresh = true (default) — refresh tokens
                // before they expire so we don't get a 401 on every call.
                // autoLoadFromStorage = true (default) — restore session
                // from `multiplatform-settings` storage on cold start.
            }
        }
}
