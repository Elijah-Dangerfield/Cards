package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Apple
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.signInAnonymously
import io.github.jan.supabase.auth.status.SessionStatus
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import io.github.jan.supabase.auth.providers.OAuthProvider as SupabaseOAuthProvider

/**
 * Production [SupabaseAuthGateway] — every method is a straight passthrough
 * to `supabase.auth.*`. Exceptions are propagated raw so
 * [SupabaseAuthRepositoryImpl] can map them to outcomes; this class
 * deliberately does not catch.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class RealSupabaseAuthGateway(
    private val supabase: SupabaseClient,
) : SupabaseAuthGateway {

    override suspend fun awaitInitialization() {
        supabase.auth.awaitInitialization()
    }

    override fun currentStatus(): AuthGatewayStatus =
        when (val status = supabase.auth.sessionStatus.value) {
            is SessionStatus.Authenticated -> AuthGatewayStatus.Authenticated
            is SessionStatus.NotAuthenticated -> AuthGatewayStatus.NotAuthenticated
            SessionStatus.Initializing -> AuthGatewayStatus.Initializing
            is SessionStatus.RefreshFailure -> AuthGatewayStatus.RefreshFailure(cause = null)
        }

    override fun currentSession(): GatewaySession? {
        val session = supabase.auth.currentSessionOrNull() ?: return null
        val user = session.user ?: return null
        // Anonymous users have no identity providers attached. supabase-kt
        // doesn't expose a first-class "is anonymous" field at this layer,
        // so we read `identities.isEmpty()`.
        val isAnon = user.identities.isNullOrEmpty()
        return GatewaySession(
            userId = user.id,
            email = if (isAnon) null else user.email,
            accessToken = session.accessToken,
            isAnonymous = isAnon,
            isEmailConfirmed = user.emailConfirmedAt != null,
        )
    }

    override suspend fun signInAnonymously() {
        supabase.auth.signInAnonymously()
    }

    override suspend fun refreshSession() {
        supabase.auth.refreshCurrentSession()
    }

    override suspend fun signInWithEmail(email: String, password: String) {
        supabase.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    override suspend fun signUpWithEmail(email: String, password: String) {
        supabase.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    override suspend fun resendVerificationEmail(email: String) {
        supabase.auth.resendEmail(OtpType.Email.SIGNUP, email = email)
    }

    override suspend fun resetPasswordForEmail(email: String) {
        supabase.auth.resetPasswordForEmail(email = email)
    }

    override suspend fun signOut() {
        supabase.auth.signOut()
    }

    override suspend fun linkOAuthIdentity(provider: OAuthProvider) {
        supabase.auth.linkIdentity(provider.toSupabaseProvider())
    }

    override suspend fun signInWithOAuth(provider: OAuthProvider) {
        supabase.auth.signInWith(provider.toSupabaseProvider())
    }

    override suspend fun linkEmailIdentity(email: String, password: String) {
        supabase.auth.updateUser {
            this.email = email
            this.password = password
        }
    }

    private fun OAuthProvider.toSupabaseProvider(): SupabaseOAuthProvider = when (this) {
        OAuthProvider.Apple -> Apple
        OAuthProvider.Google -> Google
    }
}
