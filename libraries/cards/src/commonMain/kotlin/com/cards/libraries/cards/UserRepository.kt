package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.Flow

/**
 * Central repository for user data.
 * 
 * Use this repository instead of accessing UserDao/UserCache directly.
 */
interface UserRepository {
    
    /** Ensure user entity exists in database. Creates one if not present. */
    suspend fun ensureUserExists()
    
    /** Observe the user (reactive, emits on changes) */
    fun observeUser(): Flow<User?>
    
    /** Get current user snapshot */
    suspend fun getUser(): User?
    
    /** Set the user's name */
    suspend fun setName(name: String?)
    
    /** Record that a new session started */
    suspend fun onSessionStarted()
    
    /** Increment app open count */
    suspend fun onAppOpened()
    
    /** Mark onboarding as complete */
    suspend fun setOnboardingComplete()
    
    /** Increment shake count */
    suspend fun onShakeDetected()
    
    /** Delete all user data (Fresh Start) */
    suspend fun deleteAll()
}
