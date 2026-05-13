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
    
    // Stats
    val sessionsCount: Int,
    val appOpenCount: Int,
    val shakeCount: Int = 0,
) {
    val isNewUser: Boolean get() = sessionsCount <= 1
}

