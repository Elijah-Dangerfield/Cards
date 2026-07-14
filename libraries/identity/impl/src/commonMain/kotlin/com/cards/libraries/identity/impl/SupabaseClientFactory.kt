package com.dangerfield.cards.libraries.identity.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import com.dangerfield.cards.libraries.identity.IdentityConfig
import com.dangerfield.cards.libraries.identity.auth.SecureSessionStorage
import com.dangerfield.cards.libraries.identity.impl.auth.SecureSessionManager
import com.dangerfield.cards.libraries.identity.impl.auth.SessionMirror
import com.dangerfield.cards.libraries.networking.platformHttpEngineFactory
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.createSupabaseClient
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
 * Session persistence rides a custom [SecureSessionManager] — OS-encrypted
 * storage (Keychain / EncryptedSharedPreferences) instead of supabase-kt's
 * default plaintext `multiplatform-settings` store, with a one-time silent
 * migration from that old store (AUTH-16).
 *
 * The Ktor engine is bound explicitly via [platformHttpEngineFactory]
 * (`OkHttp` on Android, `Darwin` on iOS) rather than left to supabase-kt's
 * runtime auto-resolution. On iOS auto-resolution could pick the TLS-incapable
 * native CIO engine, aborting every `POST /auth/v1/signup` with
 * "TLS sessions are not supported on Native platform" so no guest account ever
 * mints (ENG-30). ENG-28 bound the first-party clients the same way; this
 * closes the same gap on the Supabase client.
 */
@ContributesTo(AppScope::class)
interface SupabaseClientComponent {

    @SingleIn(AppScope::class)
    @Provides
    fun provideSupabaseClient(
        config: IdentityConfig,
        secureSessionStorage: SecureSessionStorage,
        sessionMirror: SessionMirror,
        dispatchers: DispatcherProvider,
    ): SupabaseClient =
        createSupabaseClient(
            supabaseUrl = config.supabaseUrl,
            supabaseKey = config.supabasePublishableKey,
        ) {
            httpEngine = platformHttpEngineFactory.create()

            install(Auth) {
                // alwaysAutoRefresh = true (default) — refresh tokens
                // before they expire so we don't get a 401 on every call.
                // autoLoadFromStorage = true (default) — restore session
                // from the session manager's storage on cold start.
                val storageKey = SecureSessionManager.storageKeyFor(config.supabaseUrl)
                sessionManager = SecureSessionManager(
                    storage = secureSessionStorage,
                    key = storageKey,
                    // The pre-AUTH-16 plaintext store, kept solely as the
                    // migration source (+ cleared on sign-out). Same key —
                    // it's exactly what the default session manager used.
                    // Catching: constructing it can only fail where no
                    // default Settings exists (never on device); losing the
                    // migration beats crashing the DI graph there.
                    legacy = Catching { SettingsSessionManager(key = storageKey) }.getOrNull(),
                    // File-backed last resort for anonymous sessions — the
                    // Keychain lost a session across a TestFlight upgrade and
                    // a guest has no way back in (AUTH-19).
                    mirror = sessionMirror,
                    dispatchers = dispatchers,
                )

                // Browser OAuth redirect target. supabase-kt builds the
                // redirect_to it sends to the provider from scheme://host on
                // Android + Apple, so a Google sign-in lands back on
                // `cards://login-callback`. The app's deep-link collector hands
                // that URL straight to [SupabaseAuthGateway.completeOAuthRedirect]
                // (see App.kt) rather than the nav graph.
                //
                // Flow stays the default IMPLICIT: the session tokens arrive in
                // the URL fragment, which `parseSessionFromUrl` reads with no
                // extra round trip. The matching `cards://login-callback` URL
                // must also be added as a redirect URL in the Supabase project's
                // Auth → URL Configuration.
                scheme = OAUTH_REDIRECT_SCHEME
                host = OAUTH_REDIRECT_HOST
            }
        }

    companion object {
        /** Deep-link scheme for the browser OAuth return trip — `cards://`. */
        const val OAUTH_REDIRECT_SCHEME = "cards"

        /** Deep-link host for the browser OAuth return trip — `…//login-callback`. */
        const val OAUTH_REDIRECT_HOST = "login-callback"
    }
}
