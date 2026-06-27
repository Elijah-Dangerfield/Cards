package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.impl.SupabaseClientComponent.Companion.OAUTH_REDIRECT_HOST
import com.dangerfield.cards.libraries.identity.impl.SupabaseClientComponent.Companion.OAUTH_REDIRECT_SCHEME
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.parseSessionFromUrl
import io.github.jan.supabase.auth.providers.Apple
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.signInAnonymously
import io.github.jan.supabase.auth.status.SessionSource
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
        // Opens the system browser to the provider's consent page. The session
        // doesn't exist yet here — it arrives later as the `cards://login-callback`
        // deep link, handled by [completeOAuthRedirect].
        supabase.auth.signInWith(provider.toSupabaseProvider())
    }

    override suspend fun completeOAuthRedirect(url: String) {
        // IMPLICIT flow: the provider returns the session tokens in the URL
        // fragment (`#access_token=…&refresh_token=…`). Parse them out and import
        // so the session is live + persisted. `parseSessionFromUrl` throws
        // IllegalArgumentException if the fragment has no tokens (user cancelled,
        // or an error redirect) — propagated raw, like every other gateway call.
        val session = supabase.auth.parseSessionFromUrl(url)
        supabase.auth.importSession(session, source = SessionSource.External)
        // `parseSessionFromUrl` carries tokens but NO user object — so
        // `sessionStatus` flips to Authenticated yet `currentSession()` (which
        // requires `session.user`) still reads null, and the caller throws
        // "no session" despite being authenticated. Fetch the user into the
        // session so it's fully hydrated before the caller reads it.
        hydrateCurrentUser()
    }

    override suspend fun hydrateCurrentUser() {
        // Fetch the user for the current session and write it back into
        // sessionStatus (updateSession = true). Two uses: after a link/claim
        // (flips is_anonymous → false, populates identities) and to hydrate a
        // tokens-only session (OAuth import / storage load) so currentSession()
        // resolves instead of reading null.
        supabase.auth.retrieveUserForCurrentSession(updateSession = true)
    }

    override fun isOAuthRedirect(url: String): Boolean =
        url.startsWith("$OAUTH_REDIRECT_SCHEME://$OAUTH_REDIRECT_HOST")

    override suspend fun signInWithAppleIdToken(idToken: String, nonce: String) {
        supabase.auth.signInWith(IDToken) {
            provider = Apple
            this.idToken = idToken
            this.nonce = nonce
        }
    }

    override suspend fun linkAppleIdToken(idToken: String, nonce: String) {
        supabase.auth.linkIdentityWithIdToken(provider = Apple, idToken = idToken) {
            this.nonce = nonce
        }
    }

    override suspend fun signInWithGoogleIdToken(idToken: String, nonce: String?) {
        supabase.auth.signInWith(IDToken) {
            provider = Google
            this.idToken = idToken
            if (nonce != null) this.nonce = nonce
        }
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
