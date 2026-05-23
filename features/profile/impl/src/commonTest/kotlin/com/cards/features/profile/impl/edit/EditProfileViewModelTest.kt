package com.dangerfield.cards.features.profile.impl.edit

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.RedeemResult
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.profile.AvatarPack
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals

class EditProfileViewModelTest : CoroutineTest() {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun submit_updateProfileCallSurvivesViewModelTeardown() = runUnitTest {
        val gate = CompletableDeferred<UpdateProfileOutcome>()
        val profile = GatedUpdateProfile(gate)
        val vm = EditProfileViewModel(
            profileRepository = profile,
            inventoryRepository = NoOpInventoryRepository,
            appScope = AppCoroutineScope(dispatchers),
        )
        runCurrent()

        vm.takeAction(EditProfileAction.DisplayNameChanged("NewName"))
        vm.takeAction(EditProfileAction.AvatarSelected("🦊"))
        vm.takeAction(EditProfileAction.Submit)
        runCurrent()
        assertEquals(1, profile.updateStarted, "update should be in-flight")

        vm.viewModelScope.coroutineContext.job.cancel()
        runCurrent()

        gate.complete(UpdateProfileOutcome.Success(sampleProfile))
        runCurrent()
        assertEquals(
            1, profile.updateFinished,
            "update must complete despite VM teardown",
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun init_awaitsInventorySync_beforeFetchingAvatarPack() = runUnitTest {
        // Regression: after buying an avatar pack, Edit Profile would
        // race a still-in-flight `inventoryRepository.sync()` call and
        // hit /v1/avatars before the server's inventory table had the
        // new row — picker showed Starter only. Fix: VM must await sync
        // before LoadAvatarPack.
        val syncGate = CompletableDeferred<Result<Unit>>()
        val inventory = GatedInventoryRepository(syncGate)
        val profile = GatedUpdateProfile(gate = CompletableDeferred())

        EditProfileViewModel(
            profileRepository = profile,
            inventoryRepository = inventory,
            appScope = AppCoroutineScope(dispatchers),
        )
        runCurrent()

        assertEquals(1, inventory.syncCalls, "sync should have been kicked")
        assertEquals(
            0, profile.fetchAvatarPackCalls,
            "fetchAvatarPack must not run until sync completes",
        )

        syncGate.complete(Result.success(Unit))
        runCurrent()
        assertEquals(
            1, profile.fetchAvatarPackCalls,
            "fetchAvatarPack must run after sync completes",
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun init_fetchesAvatarPack_evenWhenInventorySyncFails() = runUnitTest {
        // Failed sync (offline, server down) should not strand the user
        // on a blank picker — fall through to the server's avatars
        // endpoint with whatever state it has.
        val syncGate = CompletableDeferred<Result<Unit>>()
        val inventory = GatedInventoryRepository(syncGate)
        val profile = GatedUpdateProfile(gate = CompletableDeferred())

        EditProfileViewModel(
            profileRepository = profile,
            inventoryRepository = inventory,
            appScope = AppCoroutineScope(dispatchers),
        )
        runCurrent()

        syncGate.complete(Result.failure(IllegalStateException("network down")))
        runCurrent()
        assertEquals(
            1, profile.fetchAvatarPackCalls,
            "fetchAvatarPack must still run when sync fails",
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun submit_emitsSavedImmediately_withoutWaitingOnNetwork() = runUnitTest {
        val gate = CompletableDeferred<UpdateProfileOutcome>()
        val profile = GatedUpdateProfile(gate)
        val vm = EditProfileViewModel(
            profileRepository = profile,
            inventoryRepository = NoOpInventoryRepository,
            appScope = AppCoroutineScope(dispatchers),
        )
        runCurrent()

        vm.eventFlow.test {
            vm.takeAction(EditProfileAction.DisplayNameChanged("NewName"))
            vm.takeAction(EditProfileAction.AvatarSelected("🦊"))
            vm.takeAction(EditProfileAction.Submit)

            assertEquals(EditProfileEvent.Saved, awaitItem())
            assertEquals(
                1, profile.updateStarted,
                "update should be in-flight while Saved already emitted",
            )
            assertEquals(
                0, profile.updateFinished,
                "Saved must not block on the network roundtrip",
            )

            gate.complete(UpdateProfileOutcome.Success(sampleProfile))
        }
    }
}

private val sampleProfile = Profile.Authenticated(
    id = "11111111-1111-1111-1111-111111111111",
    displayName = "QuietAce72",
    avatarEmoji = "🃏",
    avatarBackgroundColor = null,
    email = null,
    isAnonymous = false,
    createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
)

private class GatedUpdateProfile(
    private val gate: CompletableDeferred<UpdateProfileOutcome>,
) : ProfileRepository {
    var updateStarted: Int = 0
        private set
    var updateFinished: Int = 0
        private set
    var fetchAvatarPackCalls: Int = 0
        private set

    private val flow = MutableSharedFlow<Profile>(replay = 1, extraBufferCapacity = 1).apply {
        tryEmit(sampleProfile)
    }

    override suspend fun current(): Profile = sampleProfile
    override fun observe(): Flow<Profile> = flow

    override suspend fun update(
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): UpdateProfileOutcome {
        updateStarted += 1
        val outcome = gate.await()
        updateFinished += 1
        return outcome
    }

    override suspend fun fetchAvatarPack(): AvatarPackOutcome {
        fetchAvatarPackCalls += 1
        return AvatarPackOutcome.Success(
            packs = listOf(AvatarPack(id = "starter", name = "Starter", emojis = listOf("🃏", "🦊"))),
            palette = emptyList(),
        )
    }
}

private class GatedInventoryRepository(
    private val gate: CompletableDeferred<Result<Unit>>,
) : InventoryRepository {
    var syncCalls: Int = 0
        private set

    override fun observeInventory(): Flow<List<InventoryItem>> = flowOf(emptyList())
    override suspend fun getInventory(): List<InventoryItem> = emptyList()
    override suspend fun redeemChipOffer(productId: String, costChips: Long): RedeemResult =
        RedeemResult.Success
    override suspend fun markConfirmed(productIds: Collection<String>) = Unit
    override suspend fun revertPurchase(productId: String) = Unit
    override suspend fun applyServerSnapshot(authoritative: List<InventoryItem>) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun sync(): Result<Unit> {
        syncCalls += 1
        return gate.await()
    }
}

private object NoOpInventoryRepository : InventoryRepository {
    override fun observeInventory(): Flow<List<InventoryItem>> = flowOf(emptyList())
    override suspend fun getInventory(): List<InventoryItem> = emptyList()
    override suspend fun redeemChipOffer(productId: String, costChips: Long): RedeemResult =
        RedeemResult.Success
    override suspend fun markConfirmed(productIds: Collection<String>) = Unit
    override suspend fun revertPurchase(productId: String) = Unit
    override suspend fun applyServerSnapshot(authoritative: List<InventoryItem>) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun sync(): Result<Unit> = Result.success(Unit)
}
