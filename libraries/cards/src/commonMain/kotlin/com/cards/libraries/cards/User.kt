package com.dangerfield.cards.libraries.cards

/**
 * Domain model representing the current user.
 * 
 * This is a simple user model that can be extended based on your app's needs.
 * This model is read-only. Use UserRepository methods to update.
 */
data class User(
    val id: String = "user", // Singleton user ID
    val name: String?,
    val createdAt: Long, // epoch millis
    val lastSessionAt: Long?, // epoch millis

    // Flags
    val hasCompletedOnboarding: Boolean,
    /**
     * True until the user claims their account via Apple/Google sign-in.
     *
     * V1 has no auth at all (everyone is anonymous), so this is synthesized
     * as `true` in [UserRepository]. Phase 3 will wire it to real Supabase
     * Auth state — at that point the field becomes the canonical signal for
     * "show me the claim-account prompts" and "rank is locked".
     */
    val isAnonymous: Boolean = true,

    // Stats
    val sessionsCount: Int,
    val appOpenCount: Int,
    val shakeCount: Int = 0,
) {
    val isNewUser: Boolean get() = sessionsCount <= 1
}

