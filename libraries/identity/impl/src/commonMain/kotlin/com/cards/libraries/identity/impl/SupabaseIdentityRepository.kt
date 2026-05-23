package com.dangerfield.cards.libraries.identity.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventBus
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
import io.github.jan.supabase.auth.signInAnonymously
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
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
 *  1. **Cold-start identity bootstrap.** On construction, optimistically
 *     emit the last-known [Identity] from the local cache for first-frame
 *     UX continuity, then run [ensureInitialized] eagerly so a valid
 *     Supabase session lands in memory before any consumer fires an authed
 *     network call. Bootstrappers can stop guarding against the "signed-in
 *     state but no token yet" race — by the time they fire,
 *     [NetworkClient]'s loadTokens gate waits on a real token regardless.
 *
 *  2. **Anonymous bootstrap.** [ensureInitialized] is the onboarding
 *     "Get Started" path — Supabase anonymous sign-in + `/v1/me`. Also
 *     called eagerly from `init` so returning users go through the same
 *     resolution, not just first-launch users.
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
    private val appEventBus: AppEventBus,
    appScope: AppCoroutineScope,
) : IdentityRepository {

    private val mutex = Mutex()
    private val _state = MutableStateFlow<IdentityState>(IdentityState.Unknown)
    override val state: StateFlow<IdentityState> = _state.asStateFlow()

    init {
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
            Catching { ensureInitialized() }
                .logOnFailure { "Eager identity bootstrap failed; a consumer call will retry" }
        }
    }

    override suspend fun ensureInitialized(): Identity = mutex.withLock {
        val current = (_state.value as? IdentityState.SignedIn)?.identity
        val hasSession = supabase.auth.currentSessionOrNull() != null
        if (current != null && hasSession) return@withLock current

        if (!hasSession) supabase.auth.signInAnonymously()
        bootstrapProfileLocked()
    }

    override suspend fun signInWithEmail(email: String, password: String): SignInOutcome = mutex.withLock {
        Catching {
            supabase.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            bootstrapProfileLocked()
        }.fold(
            onSuccess = { identity -> SignInOutcome.Success(identity) },
            onFailure = { e ->
                when (e) {
                    is RestException -> mapSignInRestException(e, email)
                    is HttpRequestException -> SignInOutcome.NetworkError(e)
                    else -> SignInOutcome.Unknown(e)
                }
            },
        )
    }

    override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome = mutex.withLock {
        Catching {
            supabase.auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
        }.fold(
            onSuccess = {
                // Supabase's response shape varies by project config: with
                // confirm-email ON (our V1 setup) the user must click the
                // verification link before we have a usable session. Verify
                // screen calls refreshSession() once that happens.
                SignUpOutcome.VerificationRequired(email)
            },
            onFailure = { e ->
                when (e) {
                    is RestException -> mapSignUpRestException(e)
                    is HttpRequestException -> SignUpOutcome.NetworkError(e)
                    else -> SignUpOutcome.Unknown(e)
                }
            },
        )
    }

    override suspend fun refreshSession(): RefreshOutcome = mutex.withLock {
        Catching {
            supabase.auth.refreshCurrentSession()
            val session = supabase.auth.currentSessionOrNull()
                ?: return@Catching RefreshOutcome.SessionExpired
            val emailConfirmed = session.user?.emailConfirmedAt != null

            if (!emailConfirmed) {
                RefreshOutcome.StillPending
            } else {
                RefreshOutcome.EmailConfirmed(bootstrapProfileLocked())
            }
        }.fold(
            onSuccess = { it },
            onFailure = { e ->
                when (e) {
                    is RestException ->
                        if (e.statusCode == 401 || e.statusCode == 403) RefreshOutcome.SessionExpired
                        else RefreshOutcome.Unknown(e)
                    is HttpRequestException -> RefreshOutcome.NetworkError(e)
                    else -> RefreshOutcome.Unknown(e)
                }
            },
        )
    }

    // Intentionally no mutex — resend doesn't mutate session state.
    override suspend fun resendVerificationEmail(email: String): ResendOutcome =
        Catching {
            supabase.auth.resendEmail(OtpType.Email.SIGNUP, email = email)
        }.fold(
            onSuccess = { ResendOutcome.Sent },
            onFailure = { e ->
                when (e) {
                    is RestException ->
                        if (e.statusCode == 429) ResendOutcome.RateLimited(retryAfterSeconds = null)
                        else ResendOutcome.Unknown(e)
                    is HttpRequestException -> ResendOutcome.NetworkError(e)
                    else -> ResendOutcome.Unknown(e)
                }
            },
        )

    override suspend fun signOut(): Unit = mutex.withLock {
        Catching { supabase.auth.signOut() }
            .logOnFailure { "Supabase signOut failed; clearing local state anyway" }
        identityCache.clear()
        _state.value = IdentityState.Unknown
        appEventBus.dispatch(AppEvent.SignedOut)
    }

    override suspend fun updateProfile(
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): UpdateProfileOutcome = mutex.withLock {
        if (supabase.auth.currentSessionOrNull() == null) {
            return@withLock UpdateProfileOutcome.NotSignedIn
        }

        val priorIdentity = (_state.value as? IdentityState.SignedIn)?.identity
        if (priorIdentity != null) {
            val optimistic = priorIdentity.copy(
                displayName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: priorIdentity.displayName,
                avatarEmoji = avatarEmoji ?: priorIdentity.avatarEmoji,
                avatarBackgroundColor = when {
                    clearAvatarBackgroundColor -> null
                    avatarBackgroundColor != null -> avatarBackgroundColor
                    else -> priorIdentity.avatarBackgroundColor
                },
            )
            identityCache.write(optimistic)
            _state.value = IdentityState.SignedIn(optimistic)
        }

        Catching {
            profileApi.patchMe(
                PatchMeRequest(
                    displayName = displayName,
                    avatarEmoji = avatarEmoji,
                    avatarBackgroundColor = avatarBackgroundColor,
                    clearAvatarBackgroundColor = clearAvatarBackgroundColor,
                ),
            )
        }.fold(
            onSuccess = { updated ->
                val identity = Identity(
                    userId = updated.userId,
                    displayName = updated.displayName,
                    avatarEmoji = updated.avatarEmoji,
                    avatarBackgroundColor = updated.avatarBackgroundColor,
                    isAnonymous = updated.isAnonymous,
                    email = supabase.auth.currentSessionOrNull()?.user?.email,
                )
                identityCache.write(identity)
                _state.value = IdentityState.SignedIn(identity)
                UpdateProfileOutcome.Success(identity)
            },
            onFailure = { e ->
                if (priorIdentity != null) {
                    identityCache.write(priorIdentity)
                    _state.value = IdentityState.SignedIn(priorIdentity)
                }
                when (e) {
                    is ClientRequestException -> when (e.response.status.value) {
                        409 -> UpdateProfileOutcome.DisplayNameTaken
                        401 -> UpdateProfileOutcome.NotSignedIn
                        400 -> when {
                            displayName != null -> UpdateProfileOutcome.InvalidDisplayName
                            avatarEmoji != null -> UpdateProfileOutcome.InvalidAvatarEmoji
                            else -> UpdateProfileOutcome.InvalidAvatarBackgroundColor
                        }
                        else -> UpdateProfileOutcome.Unknown(e)
                    }
                    is ServerResponseException -> UpdateProfileOutcome.Unknown(e)
                    else -> UpdateProfileOutcome.NetworkError(e)
                }
            },
        )
    }

    override suspend fun fetchAvatarPack(): AvatarPackOutcome =
        Catching { profileApi.avatars() }.fold(
            onSuccess = { response ->
                AvatarPackOutcome.Success(
                    packs = response.packs.map { dto ->
                        com.dangerfield.cards.libraries.identity.AvatarPack(
                            id = dto.id,
                            name = dto.name,
                            emojis = dto.emojis,
                        )
                    },
                    palette = response.backgroundPalette,
                )
            },
            onFailure = { e ->
                when (e) {
                    is ClientRequestException -> AvatarPackOutcome.Unknown(e)
                    is ServerResponseException -> AvatarPackOutcome.Unknown(e)
                    else -> AvatarPackOutcome.NetworkError(e)
                }
            },
        )

    override suspend fun deleteAccount(): DeleteAccountOutcome = mutex.withLock {
        if (supabase.auth.currentSessionOrNull() == null) {
            return@withLock DeleteAccountOutcome.NotSignedIn
        }

        val outcome = Catching { profileApi.deleteMe() }.fold(
            onSuccess = { response ->
                when (response.status.value) {
                    204, 200, 404 -> DeleteAccountOutcome.Success
                    401 -> DeleteAccountOutcome.NotSignedIn
                    503 -> DeleteAccountOutcome.NotConfigured
                    else -> DeleteAccountOutcome.Unknown(
                        IllegalStateException("Unexpected status ${response.status.value}"),
                    )
                }
            },
            onFailure = { e ->
                when (e) {
                    is ClientRequestException -> when (e.response.status.value) {
                        401 -> DeleteAccountOutcome.NotSignedIn
                        else -> DeleteAccountOutcome.Unknown(e)
                    }
                    // 503 from our server flows here because Ktor treats 5xx as
                    // server-response errors. Map it back to NotConfigured.
                    is ServerResponseException ->
                        if (e.response.status.value == 503) DeleteAccountOutcome.NotConfigured
                        else DeleteAccountOutcome.Unknown(e)
                    else -> DeleteAccountOutcome.NetworkError(e)
                }
            },
        )

        if (outcome is DeleteAccountOutcome.Success) {
            Catching { supabase.auth.signOut() }
                .logOnFailure { "Supabase signOut after delete failed; clearing local state anyway" }
            identityCache.clear()
            _state.value = IdentityState.Unknown
            appEventBus.dispatch(AppEvent.SignedOut)
        }
        outcome
    }

    override suspend fun linkOAuthIdentity(provider: IdentityOAuthProvider): LinkIdentityOutcome = mutex.withLock {
        if (supabase.auth.currentSessionOrNull() == null) {
            return@withLock LinkIdentityOutcome.NotSignedIn
        }
        Catching {
            supabase.auth.linkIdentity(provider.toSupabase())
            // After the browser flow resolves, supabase-kt updates the
            // session in place. Re-bootstrap /v1/me so the local identity
            // reflects the (now non-anonymous) JWT.
            bootstrapProfileLocked()
        }.fold(
            onSuccess = { identity -> LinkIdentityOutcome.Success(identity) },
            onFailure = { e ->
                when (e) {
                    is RestException -> mapLinkRestException(e)
                    is HttpRequestException -> LinkIdentityOutcome.NetworkError(e)
                    // Browser-dismissed cases throw various platform-specific
                    // exceptions; supabase-kt doesn't standardize. Bucket by
                    // message. Catching already rethrows CancellationException.
                    else ->
                        if ((e.message ?: "").contains("cancel", ignoreCase = true)) {
                            LinkIdentityOutcome.Cancelled
                        } else LinkIdentityOutcome.Unknown(e)
                }
            },
        )
    }

    override suspend fun signInWithOAuth(provider: IdentityOAuthProvider): SignInOutcome = mutex.withLock {
        Catching {
            supabase.auth.signInWith(provider.toSupabase())
            bootstrapProfileLocked()
        }.fold(
            onSuccess = { identity -> SignInOutcome.Success(identity) },
            onFailure = { e ->
                when (e) {
                    is RestException -> mapOAuthSignInRestException(e)
                    is HttpRequestException -> SignInOutcome.NetworkError(e)
                    else ->
                        if ((e.message ?: "").contains("cancel", ignoreCase = true)) {
                            SignInOutcome.Cancelled
                        } else SignInOutcome.Unknown(e)
                }
            },
        )
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
            avatarBackgroundColor = me.avatarBackgroundColor,
            isAnonymous = me.isAnonymous,
            email = supabase.auth.currentSessionOrNull()?.user?.email,
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
