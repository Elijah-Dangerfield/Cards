package com.dangerfield.cards.libraries.cards.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.UserMessage
import com.dangerfield.cards.libraries.cards.UserMessageKind
import com.dangerfield.cards.libraries.cards.UserMessageRepository
import com.dangerfield.cards.libraries.cards.UserMessageSyncService
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.Identity
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.IdentityState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the InAppMessageManager's display gate:
 *  - At most one dialog per foreground event
 *  - A foreground while one's still up doesn't consume another
 *  - Sync runs before consume (so freshly-arrived messages show up)
 *  - Sync failure doesn't block consume (offline-first)
 *  - SignedOut clears current
 */
class InAppMessageManagerImplTest : CoroutineTest() {

    @Test
    fun coldBoot_consumesNextDialog_andSurfacesIt() = runUnitTest {
        val repo = FakeRepo().apply { enqueue(message("a")) }
        val sync = FakeSync()
        val manager = buildManager(repo, sync)

        manager.current.test {
            assertNull(awaitItem())
            manager.onColdBoot(AppEvent.ColdBoot)
            val surfaced = awaitItem()
            assertNotNull(surfaced)
            assertEquals("a", surfaced.id)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, sync.calls, "sync runs before consume")
        assertEquals(1, repo.consumeCalls)
    }

    @Test
    fun secondForegroundWhileDialogStillUp_doesNotConsumeAnotherDialog() = runUnitTest {
        val repo = FakeRepo().apply {
            enqueue(message("a"))
            enqueue(message("b"))
        }
        val manager = buildManager(repo)
        manager.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        assertEquals("a", manager.current.value?.id)
        // Second foreground while "a" is still up.
        manager.onForeground(AppEvent.OnForeground(isColdBoot = false))
        runCurrent()
        assertEquals("a", manager.current.value?.id, "still 'a', not 'b'")
        assertEquals(1, repo.consumeCalls, "no second consume while dialog up")
    }

    @Test
    fun afterDismiss_nextForegroundConsumesNext() = runUnitTest {
        val repo = FakeRepo().apply {
            enqueue(message("a"))
            enqueue(message("b"))
        }
        val manager = buildManager(repo)
        manager.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        assertEquals("a", manager.current.value?.id)
        manager.dismissCurrent()
        assertNull(manager.current.value)

        manager.onForeground(AppEvent.OnForeground(isColdBoot = false))
        runCurrent()
        assertEquals("b", manager.current.value?.id)
    }

    @Test
    fun foreground_thatIsColdBoot_isIgnored_byForegroundBranch() = runUnitTest {
        // ColdBoot fires alongside OnForeground(isColdBoot=true). The
        // cold-boot branch owns that pass; the foreground branch must
        // skip it so we don't double-consume.
        val repo = FakeRepo().apply {
            enqueue(message("a"))
            enqueue(message("b"))
        }
        val manager = buildManager(repo)
        manager.onForeground(AppEvent.OnForeground(isColdBoot = true))
        runCurrent()
        assertNull(manager.current.value)
        assertEquals(0, repo.consumeCalls)
    }

    @Test
    fun syncFailure_stillConsumes_fromLocalCache() = runUnitTest {
        // Offline-first: a failed sync shouldn't block surfacing a
        // cached message.
        val repo = FakeRepo().apply { enqueue(message("cached")) }
        val sync = FakeSync(returns = Result.failure(RuntimeException("net")))
        val manager = buildManager(repo, sync)
        manager.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        assertEquals("cached", manager.current.value?.id)
    }

    @Test
    fun noEligibleDialog_leavesCurrentNull() = runUnitTest {
        val repo = FakeRepo() // empty
        val manager = buildManager(repo)
        manager.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        assertNull(manager.current.value)
    }

    @Test
    fun signedOut_clearsCurrent_immediately() = runUnitTest {
        val repo = FakeRepo().apply { enqueue(message("a")) }
        val manager = buildManager(repo)
        manager.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        assertNotNull(manager.current.value)
        manager.onSignedOut(AppEvent.SignedOut)
        assertNull(manager.current.value)
    }

    @Test
    fun dismissCurrent_doesNotAutomaticallySurfaceNext() = runUnitTest {
        // Dismiss without a new foreground = no new dialog. The next
        // foreground is when we re-evaluate.
        val repo = FakeRepo().apply {
            enqueue(message("a"))
            enqueue(message("b"))
        }
        val manager = buildManager(repo)
        manager.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        manager.dismissCurrent()
        runCurrent()
        assertNull(manager.current.value, "no auto-pop without a foreground event")
    }

    // ---------- scaffolding ----------

    private fun buildManager(
        repo: FakeRepo,
        sync: FakeSync = FakeSync(),
    ): InAppMessageManagerImpl {
        val identity = FakeIdentityRepo()
        return InAppMessageManagerImpl(
            repository = repo,
            syncService = sync,
            identityRepositoryProvider = { identity },
            appScope = AppCoroutineScope(dispatchers),
        )
    }

    private fun message(id: String) = UserMessage(
        id = id,
        kind = UserMessageKind.Dialog,
        emoji = null,
        title = "Title $id",
        body = "Body $id",
        deepLink = null,
        createdAtEpochMs = 0L,
        expiresAtEpochMs = null,
    )

    private class FakeRepo : UserMessageRepository {
        private val queue = ArrayDeque<UserMessage>()
        var consumeCalls: Int = 0
            private set

        fun enqueue(message: UserMessage) { queue.addLast(message) }

        override fun observeInbox(): Flow<List<UserMessage>> =
            MutableStateFlow<List<UserMessage>>(emptyList()).asStateFlow()

        override fun observeUnreadInboxCount(): Flow<Int> =
            MutableStateFlow(0).asStateFlow()

        override suspend fun consumeNextDialog(): UserMessage? {
            consumeCalls++
            return queue.removeFirstOrNull()
        }

        override suspend fun markAllInboxShown(): Int = 0
        override suspend fun replaceCache(messages: List<UserMessage>) {}
        override suspend fun pendingAckIds(): List<String> = emptyList()
    }

    private class FakeSync(
        private val returns: Result<Unit> = Result.success(Unit),
    ) : UserMessageSyncService {
        var calls: Int = 0
            private set

        override suspend fun sync(): Result<Unit> {
            calls++
            return returns
        }
    }

    private class FakeIdentityRepo : IdentityRepository {
        private val identity = Identity(
            userId = "test-user",
            displayName = "Test",
            avatarEmoji = "🙂",
            avatarBackgroundColor = null,
            isAnonymous = true,
        )
        override val state: StateFlow<IdentityState> =
            MutableStateFlow(IdentityState.SignedIn(identity)).asStateFlow()

        override suspend fun ensureInitialized(): Identity = identity
        override suspend fun signInWithEmail(
            email: String,
            password: String,
        ): com.dangerfield.cards.libraries.identity.SignInOutcome = error("unused")
        override suspend fun signUpWithEmail(
            email: String,
            password: String,
        ): com.dangerfield.cards.libraries.identity.SignUpOutcome = error("unused")
        override suspend fun refreshSession(): com.dangerfield.cards.libraries.identity.RefreshOutcome = error("unused")
        override suspend fun resendVerificationEmail(
            email: String,
        ): com.dangerfield.cards.libraries.identity.ResendOutcome = error("unused")
        override suspend fun signOut() = Unit
        override suspend fun updateProfile(
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ): com.dangerfield.cards.libraries.identity.UpdateProfileOutcome = error("unused")
        override suspend fun fetchAvatarPack():
            com.dangerfield.cards.libraries.identity.AvatarPackOutcome = error("unused")
        override suspend fun deleteAccount():
            com.dangerfield.cards.libraries.identity.DeleteAccountOutcome = error("unused")
        override suspend fun linkOAuthIdentity(
            provider: com.dangerfield.cards.libraries.identity.OAuthProvider,
        ): com.dangerfield.cards.libraries.identity.LinkIdentityOutcome = error("unused")
        override suspend fun signInWithOAuth(
            provider: com.dangerfield.cards.libraries.identity.OAuthProvider,
        ): com.dangerfield.cards.libraries.identity.SignInOutcome = error("unused")
    }
}
