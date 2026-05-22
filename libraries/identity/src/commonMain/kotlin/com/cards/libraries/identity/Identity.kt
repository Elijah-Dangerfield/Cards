package com.dangerfield.cards.libraries.identity

/**
 * Server-issued player profile, mirrored locally so feature code can read
 * the display name + avatar without re-hitting the network. Corresponds to
 * the `profiles` row on the server, which itself is keyed on Supabase's
 * `auth.users.id`.
 *
 * `userId` is the Supabase user id (UUID as a string). Treat as opaque.
 */
data class Identity(
    val userId: String,
    val displayName: String,
    val avatarEmoji: String,
    /**
     * Server-issued hex color (`#rrggbb`) shown behind the avatar emoji.
     * Null = use the theme default (the client renders surfaceSecondary
     * in that case). Picker is fed via [AvatarPackOutcome.Success.palette].
     */
    val avatarBackgroundColor: String?,
    /**
     * Whether the user is signed in anonymously (Supabase's `is_anonymous`
     * claim). Drives UI like the "claim your account" prompt; doesn't
     * affect access to most features.
     */
    val isAnonymous: Boolean,
    /**
     * Account email if the user has claimed (email-signup or OAuth-linked).
     * Null for anonymous sessions, since Supabase doesn't issue an email
     * for anon users. Sourced from `supabase.auth.currentSessionOrNull()
     * ?.user?.email` — the Supabase session is authoritative; the
     * server's `/v1/me` does not return email.
     */
    val email: String? = null,
)

/**
 * The client-side state machine for "who is this user."
 *
 * - [Unknown]: first launch before identity has been resolved.
 * - [SignedIn]: live session (anonymous or claimed). Holds the resolved profile.
 *
 * V1 has no signed-out terminal state — `signOut` flows aren't built yet.
 * When account deletion / sign-out lands, add a `SignedOut` variant here.
 */
sealed interface IdentityState {
    data object Unknown : IdentityState
    data class SignedIn(val identity: Identity) : IdentityState
}
