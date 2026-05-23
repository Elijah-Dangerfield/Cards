package com.dangerfield.cards.features.profile.impl.edit

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.PurchaseState
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
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun init_fetchesAvatarPack_withoutAwaitingInventorySync() = runUnitTest {
        // Contract flipped 2026-05-23 from "await sync before fetch" to
        // "fetch + observe inventory, filter locally." The picker no
        // longer depends on a server-side inventory join, so the
        // fetchAvatarPack call must NOT be gated on sync completion.
        val syncGate = CompletableDeferred<Result<Unit>>()
        val inventory = GatedInventoryRepository(syncGate)
        val profile = GatedUpdateProfile(gate = CompletableDeferred())

        EditProfileViewModel(
            profileRepository = profile,
            inventoryRepository = inventory,
            appScope = AppCoroutineScope(dispatchers),
        )
        runCurrent()

        assertEquals(
            1, profile.fetchAvatarPackCalls,
            "fetchAvatarPack must run immediately, not wait on sync",
        )
        assertEquals(1, inventory.syncCalls, "sync still kicked best-effort in background")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun avatarPacks_filterByLocalInventory_beforeSyncCompletes() = runUnitTest {
        // The key optimistic-pack invariant: when local inventory
        // contains the unlock product id for a premium pack, the
        // derived `avatarPacks` includes it — without waiting on the
        // server sync. Mirrors the path a fresh redeem takes (Pending
        // row → live flow → derived list includes the new pack).
        val syncGate = CompletableDeferred<Result<Unit>>()
        val ownedFlow = MutableStateFlow(emptyList<InventoryItem>())
        val inventory = ObservableInventoryRepository(syncGate, ownedFlow)
        val starter = AvatarPack("starter", "Starter pack", listOf("🦊"), unlockProductId = null)
        val animals = AvatarPack(
            id = "animals",
            name = "Animals",
            emojis = listOf("🐶"),
            unlockProductId = "avatars_animals",
        )
        val profile = GatedUpdateProfile(
            gate = CompletableDeferred(),
            packs = listOf(starter, animals),
        )

        val vm = EditProfileViewModel(
            profileRepository = profile,
            inventoryRepository = inventory,
            appScope = AppCoroutineScope(dispatchers),
        )
        runCurrent()

        // Before any inventory: starter only.
        assertEquals(listOf("starter"), vm.state.avatarPacks.map { it.id })

        // Simulate optimistic redeem of the Animals pack — local row
        // appears (Pending) before any server roundtrip.
        ownedFlow.value = listOf(
            InventoryItem(
                productId = "avatars_animals",
                state = PurchaseState.Pending,
                purchasedAtEpochMs = 0L,
                costChipsAtPurchase = 4000L,
            ),
        )
        runCurrent()

        assertEquals(
            listOf("starter", "animals"),
            vm.state.avatarPacks.map { it.id },
            "newly-owned pack must appear on the local-inventory tick, " +
                "without waiting for server sync",
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
    private val packs: List<AvatarPack> = listOf(
        AvatarPack(id = "starter", name = "Starter", emojis = listOf("🃏", "🦊"), unlockProductId = null),
    ),
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
        return AvatarPackOutcome.Success(packs = packs, palette = emptyList())
    }
}

private class ObservableInventoryRepository(
    private val gate: CompletableDeferred<Result<Unit>>,
    private val ownedFlow: MutableStateFlow<List<InventoryItem>>,
) : InventoryRepository {
    override fun observeInventory(): Flow<List<InventoryItem>> = ownedFlow
    override suspend fun getInventory(): List<InventoryItem> = ownedFlow.value
    override suspend fun redeemChipOffer(productId: String, costChips: Long): RedeemResult =
        RedeemResult.Success
    override suspend fun markConfirmed(productIds: Collection<String>) = Unit
    override suspend fun revertPurchase(productId: String) = Unit
    override suspend fun applyServerSnapshot(authoritative: List<InventoryItem>) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun sync(): Result<Unit> = gate.await()
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
