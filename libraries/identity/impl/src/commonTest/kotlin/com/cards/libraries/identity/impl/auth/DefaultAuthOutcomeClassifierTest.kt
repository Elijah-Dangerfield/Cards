package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AuthOutcome
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultAuthOutcomeClassifierTest : CoroutineTest() {

    @Test
    fun link_isLinked_withoutConsultingServerSignal() = runUnitTest {
        val profile = FakeProfileRepository(isNewAccount = true)
        val classifier = DefaultAuthOutcomeClassifier(profile)

        assertEquals(AuthOutcome.Linked, classifier.classify(wasLink = true))
        assertEquals(0, profile.resolveCalls, "a link is statically Linked; no server read")
    }

    @Test
    fun newAccount_isSignedUp() = runUnitTest {
        val classifier = DefaultAuthOutcomeClassifier(FakeProfileRepository(isNewAccount = true))
        assertEquals(AuthOutcome.SignedUp, classifier.classify())
    }

    @Test
    fun returningAccount_isSignedIn() = runUnitTest {
        val classifier = DefaultAuthOutcomeClassifier(FakeProfileRepository(isNewAccount = false))
        assertEquals(AuthOutcome.SignedIn, classifier.classify())
    }

    private class FakeProfileRepository(
        private val isNewAccount: Boolean,
    ) : ProfileRepository {
        var resolveCalls: Int = 0
            private set

        override suspend fun resolveIsNewAccount(): Boolean {
            resolveCalls++
            return isNewAccount
        }

        override suspend fun current(): Profile = error("unused")
        override fun observe(): Flow<Profile> = emptyFlow()
        override suspend fun update(
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ): UpdateProfileOutcome = error("unused")
        override suspend fun fetchAvatarPack(): AvatarPackOutcome = error("unused")
    }
}
