package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.cards.User
import com.dangerfield.cards.libraries.cards.UserRepository
import com.dangerfield.cards.libraries.cards.storage.db.UserDao
import com.dangerfield.cards.libraries.cards.storage.db.UserEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class UserRepositoryImpl(
    private val userDao: UserDao,
    private val clock: Clock,
) : UserRepository {
    
    private val logger = KLog.withTag("UserRepository")
    
    // =========================================================================
    // INITIALIZATION
    // =========================================================================
    
    override suspend fun ensureUserExists() {
        if (userDao.getUser() != null) return
        
        logger.i("Creating new user entity")
        val now = clock.now().toEpochMilliseconds()
        userDao.insert(
            UserEntity(
                id = "user",
                name = null,
                createdAt = now,
                lastSessionAt = null,
            )
        )
    }
    
    // =========================================================================
    // OBSERVE
    // =========================================================================
    
    override fun observeUser(): Flow<User?> = userDao.observeUser().map { it?.toDomain() }
    
    // =========================================================================
    // READ
    // =========================================================================
    
    override suspend fun getUser(): User? = userDao.getUser()?.toDomain()
    
    // =========================================================================
    // USER PROFILE
    // =========================================================================
    
    override suspend fun setName(name: String?) {
        userDao.updateName(name)
    }
    
    // =========================================================================
    // SESSION SIGNALS
    // =========================================================================
    
    override suspend fun onSessionStarted() {
        userDao.incrementSessionCount(clock.now().toEpochMilliseconds())
    }
    
    override suspend fun onAppOpened() {
        userDao.incrementAppOpenCount()
    }
    
    // =========================================================================
    // FLAGS
    // =========================================================================
    
    override suspend fun setOnboardingComplete() {
        userDao.setOnboardingComplete()
    }
    
    override suspend fun onShakeDetected() {
        userDao.incrementShakeCount()
    }
    
    // =========================================================================
    // RESET
    // =========================================================================
    
    override suspend fun deleteAll() {
        userDao.deleteAll()
    }
    
    // =========================================================================
    // MAPPING
    // =========================================================================
    
    private fun UserEntity.toDomain(): User = User(
        id = id,
        name = name,
        createdAt = createdAt,
        lastSessionAt = lastSessionAt,
        hasCompletedOnboarding = hasCompletedOnboarding,
        sessionsCount = sessionsCount,
        appOpenCount = appOpenCount,
        shakeCount = shakeCount,
    )
}
