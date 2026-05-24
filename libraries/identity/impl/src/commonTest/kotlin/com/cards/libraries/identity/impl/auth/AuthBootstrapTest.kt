package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the resolve loop AuthBootstrap inherited from SupabaseAuthRepositoryImpl,
 * plus the bits that are new to this class: memoization + invalidate.
 *
 * The fake gateway lives in [SupabaseAuthRepositoryImplTest.kt]; reusing it
 * means we exercise both this class and the repo's resolve via the same seam.
 */
class AuthBootstrapTest : CoroutineTest() {

    @Test
    fun awaitResolved_alreadyAuthenticated_returnsImmediately_withoutAnonSignIn() = runUnitTest {
        val session = sampleSession()
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = session,
        )
        val bootstrap = AuthBootstrap(gateway)

        val outcome = bootstrap.awaitResolved()

        assertIs<BootstrapOutcome.Authenticated>(outcome)
        assertEquals(session.userId, outcome.userId)
        assertEquals(session.isAnonymous, outcome.isAnonymous)
        assertEquals(session.email, outcome.email)
        assertEquals(0, gateway.signInAnonymouslyCalls, "no anon sign-in when already authenticated")
    }

    @Test
    fun awaitResolved_notAuthenticated_signsInAnonymously_thenReturnsAuthenticated() = runUnitTest {
        val newSession = sampleSession(userId = "anon-user-123", isAnonymous = true, email = null)
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
        )
        gateway.onSignInAnonymously = { advanceToAuthenticated(newSession) }
        val bootstrap = AuthBootstrap(gateway)

        val outcome = bootstrap.awaitResolved()

        assertIs<BootstrapOutcome.Authenticated>(outcome)
        assertEquals("anon-user-123", outcome.userId)
        assertTrue(outcome.isAnonymous)
        assertNull(outcome.email)
        assertEquals(1, gateway.signInAnonymouslyCalls)
    }

    @Test
    fun awaitResolved_anonSignInThrows_returnsFailedWithCause() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
        )
        val boom = IllegalStateException("anon sign-in disabled at project level")
        gateway.onSignInAnonymously = { throw boom }
        val bootstrap = AuthBootstrap(gateway)

        val outcome = bootstrap.awaitResolved()

        assertIs<BootstrapOutcome.Failed>(outcome)
        assertSame(boom, outcome.cause)
    }

    @Test
    fun awaitResolved_transientThenAuthenticated_loopsAndSettles() = runUnitTest {
        val session = sampleSession()
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = session,
            statusSequence = listOf(
                AuthGatewayStatus.Initializing,
                AuthGatewayStatus.RefreshFailure(cause = null),
                AuthGatewayStatus.Authenticated,
            ),
        )
        val bootstrap = AuthBootstrap(gateway)

        val outcome = bootstrap.awaitResolved()

        assertIs<BootstrapOutcome.Authenticated>(outcome)
        assertEquals(3, gateway.statusReads, "looped through Initializing + RefreshFailure before settling")
    }

    @Test
    fun awaitResolved_onlyTransient_exhaustsAttempts_returnsFailedWithNullCause() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Initializing,
            session = null,
        )
        val bootstrap = AuthBootstrap(gateway)

        val outcome = bootstrap.awaitResolved()

        assertIs<BootstrapOutcome.Failed>(outcome)
        assertNull(outcome.cause, "exhausted loop with only Transient steps reports no cause")
    }

    @Test
    fun awaitResolved_memoizes_secondCallDoesNotRePoll() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = sampleSession(),
        )
        val bootstrap = AuthBootstrap(gateway)

        val first = bootstrap.awaitResolved()
        val readsAfterFirst = gateway.statusReads
        val second = bootstrap.awaitResolved()

        assertEquals(first, second)
        assertEquals(readsAfterFirst, gateway.statusReads, "second call reuses memoized outcome")
    }

    @Test
    fun awaitResolved_concurrentCallers_shareOneResolve() = runUnitTest {
        // Block the anon sign-in until the test releases it, so two
        // concurrent awaitResolved() calls are both in flight when we
        // assert. Without the mutex they'd both poll → both sign in.
        val releaseAnon = CompletableDeferred<Unit>()
        val session = sampleSession(userId = "anon-shared", isAnonymous = true)
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
        )
        gateway.onSignInAnonymously = {
            releaseAnon.await()
            advanceToAuthenticated(session)
        }
        val bootstrap = AuthBootstrap(gateway)

        val a = async { bootstrap.awaitResolved() }
        val b = async { bootstrap.awaitResolved() }
        releaseAnon.complete(Unit)

        val outA = a.await()
        val outB = b.await()

        assertEquals(outA, outB)
        assertEquals(1, gateway.signInAnonymouslyCalls, "concurrent callers must share the resolve")
    }

    @Test
    fun invalidate_clearsMemo_nextAwaitResolvedReRuns() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
        )
        gateway.onSignInAnonymously = { advanceToAuthenticated(sampleSession(userId = "anon-1")) }
        val bootstrap = AuthBootstrap(gateway)

        bootstrap.awaitResolved()
        assertEquals(1, gateway.signInAnonymouslyCalls)

        // Simulate post-signOut: gateway goes back to NotAuthenticated, memo cleared.
        gateway.replaceSession(null)
        gateway.setStatus(AuthGatewayStatus.NotAuthenticated)
        gateway.onSignInAnonymously = { advanceToAuthenticated(sampleSession(userId = "anon-2")) }
        bootstrap.invalidate()

        val outcome = bootstrap.awaitResolved()
        assertIs<BootstrapOutcome.Authenticated>(outcome)
        assertEquals("anon-2", outcome.userId, "fresh anon sign-in fires on the second resolve")
        assertEquals(2, gateway.signInAnonymouslyCalls)
    }

    private fun sampleSession(
        userId: String = "user-1",
        isAnonymous: Boolean = false,
        email: String? = "user@example.com",
        accessToken: String = "tok-$userId",
    ): GatewaySession = GatewaySession(
        userId = userId,
        email = email,
        accessToken = accessToken,
        isAnonymous = isAnonymous,
        isEmailConfirmed = !isAnonymous,
    )
}
