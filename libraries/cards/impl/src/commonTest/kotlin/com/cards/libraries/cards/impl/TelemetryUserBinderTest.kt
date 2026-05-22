package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.Telemetry
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.Identity
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.IdentityState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryUserBinderTest : CoroutineTest() {

    @Test
    fun coldBoot_thenSignedIn_forwardsToTelemetry() = runUnitTest {
        val identity = FakeIdentity()
        val telemetry = RecordingTelemetry()
        val binder = build(identity, telemetry)

        binder.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        identity.emit(IdentityState.SignedIn(sample(id = "u1", name = "Alice")))
        runCurrent()

        assertEquals(1, telemetry.setUserCalls.size)
        assertEquals("u1", telemetry.setUserCalls.single().id)
        assertEquals("Alice", telemetry.setUserCalls.single().name)
    }

    @Test
    fun signedInBeforeColdBoot_isPickedUp() = runUnitTest {
        val identity = FakeIdentity(initial = IdentityState.SignedIn(sample(id = "u1", name = "Alice")))
        val telemetry = RecordingTelemetry()
        val binder = build(identity, telemetry)

        binder.onColdBoot(AppEvent.ColdBoot)
        runCurrent()

        assertEquals("u1", telemetry.setUserCalls.single().id)
    }

    @Test
    fun multipleColdBoots_subscribeOnce() = runUnitTest {
        val identity = FakeIdentity()
        val telemetry = RecordingTelemetry()
        val binder = build(identity, telemetry)

        binder.onColdBoot(AppEvent.ColdBoot)
        binder.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        identity.emit(IdentityState.SignedIn(sample(id = "u1", name = "Alice")))
        runCurrent()

        assertEquals(1, telemetry.setUserCalls.size, "second cold-boot should not start a second collector")
    }

    @Test
    fun unchangedIdentity_doesNotReEmit() = runUnitTest {
        val identity = FakeIdentity(initial = IdentityState.SignedIn(sample(id = "u1", name = "Alice")))
        val telemetry = RecordingTelemetry()
        val binder = build(identity, telemetry)

        binder.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        identity.emit(IdentityState.SignedIn(sample(id = "u1", name = "Alice")))
        runCurrent()

        assertEquals(1, telemetry.setUserCalls.size)
    }

    @Test
    fun displayNameChange_reEmits() = runUnitTest {
        val identity = FakeIdentity(initial = IdentityState.SignedIn(sample(id = "u1", name = "Alice")))
        val telemetry = RecordingTelemetry()
        val binder = build(identity, telemetry)

        binder.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        identity.emit(IdentityState.SignedIn(sample(id = "u1", name = "Renamed")))
        runCurrent()

        assertEquals(2, telemetry.setUserCalls.size)
        assertEquals("Renamed", telemetry.setUserCalls.last().name)
    }

    @Test
    fun signedOut_clearsTelemetryUser() = runUnitTest {
        val identity = FakeIdentity(initial = IdentityState.SignedIn(sample(id = "u1", name = "Alice")))
        val telemetry = RecordingTelemetry()
        val binder = build(identity, telemetry)

        binder.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        binder.onSignedOut(AppEvent.SignedOut)

        assertEquals(2, telemetry.setUserCalls.size)
        val cleared = telemetry.setUserCalls.last()
        assertNull(cleared.id)
        assertNull(cleared.name)
    }

    @Test
    fun unknownIdentityState_doesNotSet() = runUnitTest {
        val identity = FakeIdentity(initial = IdentityState.Unknown)
        val telemetry = RecordingTelemetry()
        val binder = build(identity, telemetry)

        binder.onColdBoot(AppEvent.ColdBoot)
        runCurrent()

        assertTrue(telemetry.setUserCalls.isEmpty())
    }

    private fun build(identity: FakeIdentity, telemetry: RecordingTelemetry) =
        TelemetryUserBinder(
            identityProvider = { identity },
            telemetry = telemetry,
            appScope = AppCoroutineScope(dispatchers),
        )

    private fun sample(id: String, name: String) = Identity(
        userId = id,
        displayName = name,
        avatarEmoji = "🙂",
        avatarBackgroundColor = null,
        isAnonymous = true,
    )

    private class RecordingTelemetry : Telemetry {
        data class SetUserCall(val email: String?, val name: String?, val id: String?)

        val setUserCalls = mutableListOf<SetUserCall>()

        override fun initialize() = Unit
        override fun setUser(email: String?, name: String?, id: String?) {
            setUserCalls += SetUserCall(email, name, id)
        }

        override fun captureUserFeedback(
            message: String,
            isBugReport: Boolean,
            eventId: String?,
            errorCode: Int?,
            email: String?,
        ) = Unit
    }

    private class FakeIdentity(initial: IdentityState = IdentityState.Unknown) : IdentityRepository {
        private val _state = MutableStateFlow(initial)
        override val state = _state

        fun emit(next: IdentityState) {
            _state.value = next
        }

        override suspend fun ensureInitialized(): Identity = error("unused")
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
