package com.dangerfield.cards.libraries.identity.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.Identity
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.IdentityState
import com.dangerfield.cards.libraries.identity.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.DeleteAccountOutcome
import com.dangerfield.cards.libraries.identity.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.OAuthProvider as IdentityOAuthProvider
import com.dangerfield.cards.libraries.identity.RefreshOutcome
import com.dangerfield.cards.libraries.identity.ResendOutcome
import com.dangerfield.cards.libraries.identity.SignInOutcome
import com.dangerfield.cards.libraries.identity.SignUpOutcome
import com.dangerfield.cards.libraries.identity.UpdateProfileOutcome
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Apple
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.OAuthProvider as SupabaseOAuthProvider
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Owns three things, fused so the rest of the app doesn't have to think
 * about any of them:
 *
 *  1. **Cold-start offline display.** On construction, lazily read the
 *     last-known [Identity] from the local cache and emit `SignedIn`
 *     immediately. Feature code that observes [state] sees a real
 *     identity within the first composition, even with no network.
 *
 *  2. **Anonymous bootstrap.** [ensureInitialized] is the onboarding
 *     "Get Started" path — Supabase anonymous sign-in + `/v1/me`.
 *
 *  3. **Email/password auth.** [signInWithEmail], [signUpWithEmail],
 *     [refreshSession], [resendVerificationEmail], [signOut] handle the
 *     real-account flow. The signed-in user is treated as authoritative —
 *     any prior anonymous session is replaced (Supabase doesn't merge;
 *     see decisions.md for the V1 UX of "anon orphan acceptable").
 *
 * The mutex serializes all session-mutating operations against the
 * `state` flow and the cache, so concurrent callers never see a torn
 * view.
 *
 * Errors are returned as sealed outcome types rather than thrown, because
 * the UI wants to render specific messages for "invalid credentials" vs
 * "network down" vs "email already registered." Try/catch at every call
 * site was the worse alternative.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class SupabaseIdentityRepository(
    private val supabase: SupabaseClient,
    private val profileApi: ProfileApi,
    private val identityCache: IdentityCache,
    appScope: AppCoroutineScope,
) : IdentityRepository {

    private val mutex = Mutex()
    private val _state = MutableStateFlow<IdentityState>(IdentityState.Unknown)
    override val state: StateFlow<IdentityState> = _state.asStateFlow()

    init {
        // Fire-and-forget cache hydration. If the user has signed in before,
        // their cached profile appears in [state] within the first frame
        // post-launch — features render with real data instead of skeletons.
        appScope.launch {
            val cached = Catching { identityCache.read() }
                .logOnFailure { "Failed to read cached identity" }
                .getOrNull()
            if (cached != null) {
                mutex.withLock {
                    if (_state.value is IdentityState.Unknown) {
                        _state.value = IdentityState.SignedIn(cached)
                    }
                }
            }
        }
    }

    override suspend fun ensureInitialized(): Identity = mutex.withLock {
        (_state.value as? IdentityState.SignedIn)?.let { return@withLock it.identity }

        if (supabase.auth.currentSessionOrNull() == null) {
            supabase.auth.signInAnonymously()
        }
        bootstrapProfileLocked()
    }

    override suspend fun signInWithEmail(email: String, password: String): SignInOutcome = mutex.withLock {
        val emailArg = email
        val passwordArg = password
        try {
            supabase.auth.signInWith(Email) {
                this.email = emailArg
                this.password = passwordArg
            }
            val identity = bootstrapProfileLocked()
            SignInOutcome.Success(identity)
        } catch (e: RestException) {
            mapSignInRestException(e, emailArg)
        } catch (e: HttpRequestException) {
            SignInOutcome.NetworkError(e)
        } catch (e: Throwable) {
            SignInOutcome.Unknown(e)
        }
    }

    override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome = mutex.withLock {
        val emailArg = email
        val passwordArg = password
        try {
            supabase.auth.signUpWith(Email) {
                this.email = emailArg
                this.password = passwordArg
            }
            // Supabase's response shape varies by project config:
            //  - "Confirm email" ON  → returns user, no session, sends email.
            //  - "Confirm email" OFF → returns user + session immediately.
            // Our project has email confirmation ON for V1, so we always
            // expect the verification flow. The verify screen calls
            // refreshSession() once the user clicks the email link.
            SignUpOutcome.VerificationRequired(emailArg)
        } catch (e: RestException) {
            mapSignUpRestException(e)
        } catch (e: HttpRequestException) {
            SignUpOutcome.NetworkError(e)
        } catch (e: Throwable) {
            SignUpOutcome.Unknown(e)
        }
    }

    override suspend fun refreshSession(): RefreshOutcome = mutex.withLock {
        try {
            supabase.auth.refreshCurrentSession()
            val session = supabase.auth.currentSessionOrNull()
                ?: return@withLock RefreshOutcome.SessionExpired
            val emailConfirmed = session.user?.emailConfirmedAt != null

            if (!emailConfirmed) {
                RefreshOutcome.StillPending
            } else {
                val identity = bootstrapProfileLocked()
                RefreshOutcome.EmailConfirmed(identity)
            }
        } catch (e: RestException) {
            // 401 / 403 here means the session is gone or revoked. Anything
            // else (5xx, unexpected) goes to Unknown.
            if (e.statusCode == 401 || e.statusCode == 403) RefreshOutcome.SessionExpired
            else RefreshOutcome.Unknown(e)
        } catch (e: HttpRequestException) {
            RefreshOutcome.NetworkError(e)
        } catch (e: Throwable) {
            RefreshOutcome.Unknown(e)
        }
    }

    override suspend fun resendVerificationEmail(email: String): ResendOutcome {
        // No mutex — resend doesn't mutate session state.
        return try {
            supabase.auth.resendEmail(OtpType.Email.SIGNUP, email = email)
            ResendOutcome.Sent
        } catch (e: RestException) {
            // Supabase returns 429 when its rate-limiter throttles. We
            // don't parse the retry-after hint for V1 — the UI shows a
            // generic "try again in a minute" message.
            if (e.statusCode == 429) ResendOutcome.RateLimited(retryAfterSeconds = null)
            else ResendOutcome.Unknown(e)
        } catch (e: HttpRequestException) {
            ResendOutcome.NetworkError(e)
        } catch (e: Throwable) {
            ResendOutcome.Unknown(e)
        }
    }

    override suspend fun signOut(): Unit = mutex.withLock {
        Catching { supabase.auth.signOut() }
            .logOnFailure { "Supabase signOut failed; clearing local state anyway" }
        identityCache.clear()
        _state.value = IdentityState.Unknown
    }

    override suspend fun updateProfile(
        displayName: String?,
        avatarEmoji: String?,
    ): UpdateProfileOutcome = mutex.withLock {
        if (supabase.auth.currentSessionOrNull() == null) {
            return@withLock UpdateProfileOutcome.NotSignedIn
        }

        try {
            val updated = profileApi.patchMe(PatchMeRequest(displayName, avatarEmoji))
            val identity = Identity(
                userId = updated.userId,
                displayName = updated.displayName,
                avatarEmoji = updated.avatarEmoji,
                isAnonymous = updated.isAnonymous,
            )
            identityCache.write(identity)
            _state.value = IdentityState.SignedIn(identity)
            UpdateProfileOutcome.Success(identity)
        } catch (e: ClientRequestException) {
            when (e.response.status.value) {
                409 -> UpdateProfileOutcome.DisplayNameTaken
                401 -> UpdateProfileOutcome.NotSignedIn
                400 -> {
                    // Server returns 400 for both invalid_display_name and
                    // invalid_avatar_emoji; we infer which based on what
                    // the caller submitted. UI shows a single field's
                    // error message either way.
                    if (displayName != null) UpdateProfileOutcome.InvalidDisplayName
                    else UpdateProfileOutcome.InvalidAvatarEmoji
                }
                else -> UpdateProfileOutcome.Unknown(e)
            }
        } catch (e: ServerResponseException) {
            UpdateProfileOutcome.Unknown(e)
        } catch (e: Throwable) {
            // Network failures, timeouts, no DNS, no host, etc. all land
            // here. V1 doesn't drill in; UI shows "couldn't reach server."
            UpdateProfileOutcome.NetworkError(e)
        }
    }

    override suspend fun fetchAvatarPack(): AvatarPackOutcome = try {
        val pack = profileApi.avatars()
        AvatarPackOutcome.Success(pack.starter)
    } catch (e: ClientRequestException) {
        AvatarPackOutcome.Unknown(e)
    } catch (e: ServerResponseException) {
        AvatarPackOutcome.Unknown(e)
    } catch (e: Throwable) {
        AvatarPackOutcome.NetworkError(e)
    }

    override suspend fun deleteAccount(): DeleteAccountOutcome = mutex.withLock {
        if (supabase.auth.currentSessionOrNull() == null) {
            return@withLock DeleteAccountOutcome.NotSignedIn
        }

        val outcome = try {
            val response = profileApi.deleteMe()
            when (response.status.value) {
                204, 200, 404 -> DeleteAccountOutcome.Success
                401 -> DeleteAccountOutcome.NotSignedIn
                503 -> DeleteAccountOutcome.NotConfigured
                else -> DeleteAccountOutcome.Unknown(IllegalStateException("Unexpected status ${response.status.value}"))
            }
        } catch (e: ClientRequestException) {
            when (e.response.status.value) {
                401 -> DeleteAccountOutcome.NotSignedIn
                else -> DeleteAccountOutcome.Unknown(e)
            }
        } catch (e: ServerResponseException) {
            // 503 from our server flows here because Ktor treats 5xx as
            // server-response errors. Map it back to NotConfigured before
            // we get to the catch-all.
            if (e.response.status.value == 503) DeleteAccountOutcome.NotConfigured
            else DeleteAccountOutcome.Unknown(e)
        } catch (e: Throwable) {
            DeleteAccountOutcome.NetworkError(e)
        }

        if (outcome is DeleteAccountOutcome.Success) {
            // Tear down the local session regardless of which success path
            // we took. We hold the mutex, so we can flip cache + state safely.
            Catching { supabase.auth.signOut() }
                .logOnFailure { "Supabase signOut after delete failed; clearing local state anyway" }
            identityCache.clear()
            _state.value = IdentityState.Unknown
        }
        outcome
    }

    override suspend fun linkOAuthIdentity(provider: IdentityOAuthProvider): LinkIdentityOutcome = mutex.withLock {
        if (supabase.auth.currentSessionOrNull() == null) {
            return@withLock LinkIdentityOutcome.NotSignedIn
        }
        try {
            supabase.auth.linkIdentity(provider.toSupabase())
            // After the browser flow resolves, supabase-kt updates the
            // session in place. Re-bootstrap /v1/me so the local identity
            // reflects the (now non-anonymous) JWT.
            val identity = bootstrapProfileLocked()
            LinkIdentityOutcome.Success(identity)
        } catch (e: RestException) {
            mapLinkRestException(e)
        } catch (e: HttpRequestException) {
            LinkIdentityOutcome.NetworkError(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Browser-dismissed cases throw various platform-specific
            // exceptions; supabase-kt doesn't standardize. Best we can do
            // is bucket the message.
            if ((e.message ?: "").contains("cancel", ignoreCase = true)) {
                LinkIdentityOutcome.Cancelled
            } else LinkIdentityOutcome.Unknown(e)
        }
    }

    override suspend fun signInWithOAuth(provider: IdentityOAuthProvider): SignInOutcome = mutex.withLock {
        try {
            supabase.auth.signInWith(provider.toSupabase())
            val identity = bootstrapProfileLocked()
            SignInOutcome.Success(identity)
        } catch (e: RestException) {
            mapOAuthSignInRestException(e)
        } catch (e: HttpRequestException) {
            SignInOutcome.NetworkError(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if ((e.message ?: "").contains("cancel", ignoreCase = true)) {
                SignInOutcome.Cancelled
            } else SignInOutcome.Unknown(e)
        }
    }

    private fun mapLinkRestException(e: RestException): LinkIdentityOutcome {
        val msg = (e.message ?: "").lowercase()
        return when {
            e.statusCode == 422 && msg.contains("already") ->
                LinkIdentityOutcome.AlreadyOnAnotherAccount
            e.statusCode == 400 && msg.contains("provider") && msg.contains("disabled") ->
                LinkIdentityOutcome.ProviderNotEnabled
            else -> LinkIdentityOutcome.Unknown(e)
        }
    }

    private fun mapOAuthSignInRestException(e: RestException): SignInOutcome {
        val msg = (e.message ?: "").lowercase()
        return when {
            e.statusCode == 400 && msg.contains("provider") && msg.contains("disabled") ->
                SignInOutcome.ProviderNotEnabled
            else -> SignInOutcome.Unknown(e)
        }
    }

    private fun IdentityOAuthProvider.toSupabase(): SupabaseOAuthProvider = when (this) {
        IdentityOAuthProvider.Google -> Google
        IdentityOAuthProvider.Apple -> Apple
    }

    /**
     * Inside-the-mutex helper: assumes a valid Supabase session exists,
     * calls `/v1/me` to bootstrap the profile, updates cache + state,
     * returns the identity. The server reads `is_anonymous` from the JWT
     * claim on every request and reflects it on the response — we trust
     * that rather than tracking it from the call site that signed in.
     */
    private suspend fun bootstrapProfileLocked(): Identity {
        val me = profileApi.me()
        val identity = Identity(
            userId = me.userId,
            displayName = me.displayName,
            avatarEmoji = me.avatarEmoji,
            isAnonymous = me.isAnonymous,
        )
        identityCache.write(identity)
        _state.value = IdentityState.SignedIn(identity)
        return identity
    }

    private fun mapSignInRestException(e: RestException, email: String): SignInOutcome {
        val msg = (e.message ?: "").lowercase()
        return when {
            e.statusCode == 400 && msg.contains("invalid login credentials") ->
                SignInOutcome.InvalidCredentials
            e.statusCode == 400 && msg.contains("email not confirmed") ->
                SignInOutcome.EmailNotConfirmed(email)
            else -> SignInOutcome.Unknown(e)
        }
    }

    private fun mapSignUpRestException(e: RestException): SignUpOutcome {
        val msg = (e.message ?: "").lowercase()
        return when {
            msg.contains("user already registered") ||
                (msg.contains("email address") && msg.contains("already")) ->
                SignUpOutcome.EmailAlreadyRegistered
            msg.contains("password") && msg.contains("at least") ->
                SignUpOutcome.WeakPassword
            msg.contains("invalid email") || msg.contains("unable to validate email") ->
                SignUpOutcome.InvalidEmail
            else -> SignUpOutcome.Unknown(e)
        }
    }
}
