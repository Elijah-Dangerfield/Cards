package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.Telemetry
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryUserBinderTest : CoroutineTest() {

    @Test
    fun coldBoot_thenAuthenticatedProfile_forwardsToTelemetry() = runUnitTest {
        val profile = FakeProfile()
        val telemetry = RecordingTelemetry()
        val binder = build(profile, telemetry)

        binder.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        profile.emit(sample(id = "u1", name = "Alice"))
        runCurrent()

        assertEquals(1, telemetry.setUserCalls.size)
        assertEquals("u1", telemetry.setUserCalls.single().id)
        assertEquals("Alice", telemetry.setUserCalls.single().name)
    }

    @Test
    fun emittedBeforeColdBoot_isPickedUp() = runUnitTest {
        val profile = FakeProfile()
        val telemetry = RecordingTelemetry()
        val binder = build(profile, telemetry)

        // Replay = 1 so the latest emission is available to a late
        // subscriber, mirroring SharedFlow(replay = 1) in the real impl.
        profile.emit(sample(id = "u1", name = "Alice"))
        binder.onColdBoot(AppEvent.ColdBoot)
        runCurrent()

        assertEquals("u1", telemetry.setUserCalls.single().id)
    }

    @Test
    fun multipleColdBoots_subscribeOnce() = runUnitTest {
        val profile = FakeProfile()
        val telemetry = RecordingTelemetry()
        val binder = build(profile, telemetry)

        binder.onColdBoot(AppEvent.ColdBoot)
        binder.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        profile.emit(sample(id = "u1", name = "Alice"))
        runCurrent()

        assertEquals(1, telemetry.setUserCalls.size, "second cold-boot should not start a second collector")
    }

    @Test
    fun unchangedProfile_doesNotReEmit() = runUnitTest {
        val profile = FakeProfile()
        val telemetry = RecordingTelemetry()
        val binder = build(profile, telemetry)

        profile.emit(sample(id = "u1", name = "Alice"))
        binder.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        profile.emit(sample(id = "u1", name = "Alice"))
        runCurrent()

        assertEquals(1, telemetry.setUserCalls.size)
    }

    @Test
    fun displayNameChange_reEmits() = runUnitTest {
        val profile = FakeProfile()
        val telemetry = RecordingTelemetry()
        val binder = build(profile, telemetry)

        profile.emit(sample(id = "u1", name = "Alice"))
        binder.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        profile.emit(sample(id = "u1", name = "Renamed"))
        runCurrent()

        assertEquals(2, telemetry.setUserCalls.size)
        assertEquals("Renamed", telemetry.setUserCalls.last().name)
    }

    @Test
    fun signedOut_clearsTelemetryUser() = runUnitTest {
        val profile = FakeProfile()
        val telemetry = RecordingTelemetry()
        val binder = build(profile, telemetry)

        profile.emit(sample(id = "u1", name = "Alice"))
        binder.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        // current == null → a true sign-out / delete clears the bound user.
        binder.onUserChanged(AppEvent.UserChanged(previous = "u1", current = null))

        assertEquals(2, telemetry.setUserCalls.size)
        val cleared = telemetry.setUserCalls.last()
        assertNull(cleared.id)
        assertNull(cleared.name)
    }

    @Test
    fun accountSwitch_doesNotClearTelemetryUser() = runUnitTest {
        // On a switch (current != null) the profile observer re-binds telemetry
        // to the incoming user; UserChanged itself must not null it out.
        val profile = FakeProfile()
        val telemetry = RecordingTelemetry()
        val binder = build(profile, telemetry)

        profile.emit(sample(id = "u1", name = "Alice"))
        binder.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        binder.onUserChanged(AppEvent.UserChanged(previous = "u1", current = "u2"))

        assertEquals(1, telemetry.setUserCalls.size, "switch must not push a clear call")
        assertEquals("u1", telemetry.setUserCalls.last().id)
    }

    @Test
    fun email_forwardedWhenPresent() = runUnitTest {
        val profile = FakeProfile()
        val telemetry = RecordingTelemetry()
        val binder = build(profile, telemetry)

        profile.emit(sample(id = "u1", name = "Alice", email = "alice@example.com"))
        binder.onColdBoot(AppEvent.ColdBoot)
        runCurrent()

        assertEquals("alice@example.com", telemetry.setUserCalls.single().email)
    }

    @Test
    fun emailChange_reEmits() = runUnitTest {
        val profile = FakeProfile()
        val telemetry = RecordingTelemetry()
        val binder = build(profile, telemetry)

        profile.emit(sample(id = "u1", name = "Alice", email = null))
        binder.onColdBoot(AppEvent.ColdBoot)
        runCurrent()
        profile.emit(sample(id = "u1", name = "Alice", email = "alice@example.com"))
        runCurrent()

        assertEquals(2, telemetry.setUserCalls.size)
        assertNull(telemetry.setUserCalls.first().email)
        assertEquals("alice@example.com", telemetry.setUserCalls.last().email)
    }

    @Test
    fun fallbackProfile_doesNotSet() = runUnitTest {
        // Fallback isn't a real user — no telemetry attribution to do.
        val profile = FakeProfile()
        val telemetry = RecordingTelemetry()
        val binder = build(profile, telemetry)

        profile.emit(Profile.Fallback(id = "local-uuid"))
        binder.onColdBoot(AppEvent.ColdBoot)
        runCurrent()

        assertTrue(telemetry.setUserCalls.isEmpty())
    }

    private fun build(profile: FakeProfile, telemetry: RecordingTelemetry) =
        TelemetryUserBinder(
            profileProvider = { profile },
            telemetry = telemetry,
            appScope = AppCoroutineScope(dispatchers),
        )

    private fun sample(id: String, name: String, email: String? = null) = Profile.Authenticated(
        id = id,
        displayName = name,
        avatarEmoji = "🙂",
        avatarBackgroundColor = null,
        isAnonymous = true,
        email = email,
        createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
    )

    private class RecordingTelemetry : Telemetry {
        data class SetUserCall(val email: String?, val name: String?, val id: String?)

        val setUserCalls = mutableListOf<SetUserCall>()
        val setRouteCalls = mutableListOf<String>()

        override fun initialize() = Unit
        override fun setUser(email: String?, name: String?, id: String?) {
            setUserCalls += SetUserCall(email, name, id)
        }

        override fun setCurrentRoute(route: String) {
            setRouteCalls += route
        }

        override fun setSession(sessionId: String) = Unit

        override fun setInstallId(installId: String) = Unit

        override fun captureUserFeedback(
            message: String,
            isBugReport: Boolean,
            eventId: String?,
            errorCode: Int?,
            email: String?,
        ) = Unit
    }

    private class FakeProfile : ProfileRepository {
        private val flow = MutableSharedFlow<Profile>(replay = 1, extraBufferCapacity = 8)

        fun emit(profile: Profile) {
            flow.tryEmit(profile)
        }

        override suspend fun current(): Profile = error("not used in binder tests")
        override fun observe(): Flow<Profile> = flow
        override suspend fun update(
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ): UpdateProfileOutcome = error("unused")
        override suspend fun fetchAvatarPack(): AvatarPackOutcome = error("unused")
    }
}
