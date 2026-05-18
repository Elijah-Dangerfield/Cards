package com.dangerfield.cards.libraries.identity.impl

import com.dangerfield.cards.libraries.networking.AuthTokenProvider
import com.dangerfield.cards.libraries.networking.NoOpAuthTokenProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Surfaces the Supabase-issued access token to the networking layer.
 *
 * `supabase-kt` owns the actual JWT lifecycle:
 *  - On cold start, it auto-loads any persisted session from storage.
 *  - It silently refreshes the access token before expiry on a background
 *    coroutine.
 *  - It exposes `currentAccessTokenOrNull()` as a sync read of the current
 *    valid token, or `null` if no session.
 *
 * So `getAccessToken()` is a fast in-memory read. We don't implement our
 * own `refreshAccessToken()` — when Ktor's bearer plugin asks for a fresh
 * token after a 401, we just ask supabase-kt again; it will have either
 * refreshed by then or we'll return null (caller treats as unauth).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [NoOpAuthTokenProvider::class])
@Inject
class SupabaseAuthTokenProvider(
    private val supabase: SupabaseClient,
) : AuthTokenProvider {

    override suspend fun getAccessToken(): String? =
        supabase.auth.currentSessionOrNull()?.accessToken

    override suspend fun refreshAccessToken(): String? {
        // If our token was rejected, the most likely cause is that the
        // local cache is stale. Force a refresh via supabase-kt and
        // return the new value (or null on hard failure).
        return try {
            supabase.auth.refreshCurrentSession()
            supabase.auth.currentSessionOrNull()?.accessToken
        } catch (_: Throwable) {
            null
        }
    }
}
