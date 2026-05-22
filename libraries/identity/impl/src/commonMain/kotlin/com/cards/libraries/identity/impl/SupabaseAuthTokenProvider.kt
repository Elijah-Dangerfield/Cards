package com.dangerfield.cards.libraries.identity.impl

import com.dangerfield.cards.libraries.networking.AuthTokenProvider
import com.dangerfield.cards.libraries.networking.NoOpAuthTokenProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Duration

/**
 * Surfaces the Supabase-issued access token to the networking layer.
 *
 * `supabase-kt` owns the actual JWT lifecycle:
 *  - On cold start, it auto-loads any persisted session from storage.
 *  - It silently refreshes the access token before expiry on a background
 *    coroutine.
 *  - It exposes `currentSessionOrNull()` as a sync read of the current
 *    session, or `null` if no session.
 *
 * `getAccessToken()` is a fast in-memory read. [awaitAccessToken] polls
 * the same source so [com.dangerfield.cards.libraries.networking.NetworkClient]'s
 * `loadTokens` can suspend on cold boot until the session restore (or
 * eager anonymous sign-in) lands a token — rather than firing without a
 * bearer and eating a 401. Polling at [PollIntervalMs]ms is cheap; the
 * session typically resolves in <500ms on cold start.
 *
 * `refreshAccessToken()` is called by Ktor's bearer plugin on 401 — we
 * just ask supabase-kt to refresh; it will have either refreshed by
 * then or we return null (caller treats as unauth).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [NoOpAuthTokenProvider::class])
@Inject
class SupabaseAuthTokenProvider(
    private val supabase: SupabaseClient,
) : AuthTokenProvider {

    override suspend fun getAccessToken(): String? =
        supabase.auth.currentSessionOrNull()?.accessToken

    override suspend fun awaitAccessToken(timeout: Duration): String? =
        withTimeoutOrNull(timeout) {
            var token = getAccessToken()
            while (token == null) {
                delay(PollIntervalMs)
                token = getAccessToken()
            }
            token
        }

    override suspend fun refreshAccessToken(): String? {
        return try {
            supabase.auth.refreshCurrentSession()
            supabase.auth.currentSessionOrNull()?.accessToken
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        const val PollIntervalMs: Long = 50
    }
}
