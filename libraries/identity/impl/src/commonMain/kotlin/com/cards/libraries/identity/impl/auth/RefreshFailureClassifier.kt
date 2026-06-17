package com.dangerfield.cards.libraries.identity.impl.auth

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException

/**
 * How a session-refresh failure should be treated.
 *
 * Only [AuthRejected] boots the user — a server-confirmed rejection of our
 * token. Every ambiguous or network failure is [Transient]: keep the session,
 * fall back to cache, surface the offline indicator. Booting on a maybe is
 * destructive (a guest's progress is unrecoverable), so the bar is deliberately
 * high — anything we don't positively recognize as a rejection stays transient.
 */
internal enum class RefreshFailureKind { AuthRejected, Transient }

/**
 * Classify the exception thrown by a session refresh. Mirrors the existing
 * mapping in `SupabaseAuthRepositoryImpl.refreshSession`: a [RestException] with
 * a 401/403 status is the auth server rejecting our token; an
 * [HttpRequestException] is the network being down; anything else (including
 * 5xx and unrecognized errors) is treated as transient — we don't boot on it.
 */
internal fun classifyRefreshFailure(error: Throwable): RefreshFailureKind = when (error) {
    is HttpRequestException -> RefreshFailureKind.Transient
    is RestException ->
        if (error.statusCode == 401 || error.statusCode == 403) {
            RefreshFailureKind.AuthRejected
        } else {
            RefreshFailureKind.Transient
        }
    else -> RefreshFailureKind.Transient
}
