package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.Session
import com.dangerfield.cards.libraries.cards.SessionRepository
import com.dangerfield.cards.libraries.cards.UserRepository
import com.dangerfield.cards.libraries.cards.storage.db.SessionDao
import com.dangerfield.cards.libraries.cards.storage.db.SessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val SESSION_TIMEOUT = 10.minutes

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = SessionRepository::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class SessionRepositoryImpl(
    private val sessionDao: SessionDao,
    private val userRepository: UserRepository,
    private val appScope: AppCoroutineScope,
    private val clock: Clock
) : SessionRepository, AppEventListener {

    private val logger = KLog.withTag("SessionRepository")

    private val _currentSession = MutableStateFlow<Session?>(null)
    override val currentSession: StateFlow<Session?> = _currentSession.asStateFlow()

    private var lastBackgroundedAt: Instant? = null

    override fun onForeground(event: AppEvent.OnForeground) {
        logger.d("onForeground isColdBoot=${event.isColdBoot}")
        appScope.launch {
            startOrResumeSession()
        }
    }

    override fun onBackground(event: AppEvent.OnBackground) {
        logger.d("onBackground")
        lastBackgroundedAt = clock.now()
    }

    override suspend fun startOrResumeSession() {
        val shouldCreateNewSession = when {
            _currentSession.value == null -> true
            hasSessionTimedOut() -> true
            else -> false
        }

        if (shouldCreateNewSession) {
            createNewSession()
        }

        lastBackgroundedAt = null
    }

    override suspend fun endCurrentSession() {
        val session = _currentSession.value ?: return
        val now = clock.now()
        
        sessionDao.update(
            SessionEntity(
                id = session.id,
                startedAt = session.startedAt,
                endedAt = now,
                previousSessionId = null,
            )
        )
        
        _currentSession.value = null
    }

    private fun hasSessionTimedOut(): Boolean {
        val backgroundedAt = lastBackgroundedAt ?: return false
        val elapsed = clock.now() - backgroundedAt
        return elapsed >= SESSION_TIMEOUT
    }

    private suspend fun createNewSession() {
        val now = clock.now()
        val sessionCount = sessionDao.getSessionCount()
        val previousSession = sessionDao.getLatestSession()

        val newSession = Session(
            id = Uuid.random().toString(),
            sessionNumber = sessionCount + 1,
            startedAt = now,
        )

        sessionDao.insert(
            SessionEntity(
                id = newSession.id,
                startedAt = newSession.startedAt,
                previousSessionId = previousSession?.id,
            )
        )

        _currentSession.update { newSession }
        userRepository.onSessionStarted()
        
        logger.i("Created new session #${newSession.sessionNumber}")
    }
}
