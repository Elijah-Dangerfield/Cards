package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for managing user sessions.
 */
interface SessionRepository {
    /** The current active session, or null if none */
    val currentSession: StateFlow<Session?>
    
    /** Start a new session or resume an existing one */
    suspend fun startOrResumeSession()
    
    /** End the current session */
    suspend fun endCurrentSession()
}
