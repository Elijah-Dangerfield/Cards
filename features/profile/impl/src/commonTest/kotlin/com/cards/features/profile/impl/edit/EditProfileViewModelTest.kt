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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun avatarPacks_flipLockedByLocalInventory_beforeSyncCompletes() = runUnitTest {
        // Contract (2026-05-23 revision): every server pack is always
        // present in the derived `avatarPacks` — locked premium packs
        // render dimmed with a "Get in shop" CTA. The invariant pinned
        // here is that ownership flips a pack from locked → unlocked
        // off the local-inventory flow, without waiting on the server
        // sync. Mirrors the optimistic redeem path (Pending row →
        // live flow → isLocked = false on the next tick).
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

        // Before any inventory: both packs present, premium pack locked.
        assertEquals(
            listOf("starter" to false, "animals" to true),
            vm.state.avatarPacks.map { it.pack.id to it.isLocked },
        )

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
            listOf("starter" to false, "animals" to false),
            vm.state.avatarPacks.map { it.pack.id to it.isLocked },
            "owned premium pack must unlock on the local-inventory tick, " +
                "without waiting for server sync",
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun avatarPacks_renderUnlockedPacksBeforeLockedOnes() = runUnitTest {
        // The picker puts what the user can *actually* pick from at the
        // top — Starter + any owned premium packs — and pushes locked
        // upsell rows below. Server order is preserved within each
        // group via stable sort; if the server returns
        // [Starter, Animals(locked), Food(unlocked), Fantasy(locked)],
        // the picker renders [Starter, Food, Animals, Fantasy] so the
        // user's choices sit contiguous at the top.
        val ownedFlow = MutableStateFlow(
            listOf(
                InventoryItem(
                    productId = "avatars_food",
                    state = PurchaseState.Confirmed,
                    purchasedAtEpochMs = 0L,
                    costChipsAtPurchase = 4000L,
                ),
            ),
        )
        val inventory = ObservableInventoryRepository(
            gate = CompletableDeferred(),
            ownedFlow = ownedFlow,
        )
        val starter = AvatarPack("starter", "Starter", listOf("🦊"), unlockProductId = null)
        val animals = AvatarPack("animals", "Animals", listOf("🐶"), unlockProductId = "avatars_animals")
        val food = AvatarPack("food", "Food", listOf("🍕"), unlockProductId = "avatars_food")
        val fantasy = AvatarPack("fantasy", "Fantasy", listOf("🧙"), unlockProductId = "avatars_fantasy")

        val vm = EditProfileViewModel(
            profileRepository = GatedUpdateProfile(
                gate = CompletableDeferred(),
                packs = listOf(starter, animals, food, fantasy),
            ),
            inventoryRepository = inventory,
            appScope = AppCoroutineScope(dispatchers),
        )
        runCurrent()

        assertEquals(
            listOf("starter" to false, "food" to false, "animals" to true, "fantasy" to true),
            vm.state.avatarPacks.map { it.pack.id to it.isLocked },
            "unlocked packs render first; server order is preserved within each group",
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun submit_avatarOnlyChange_emitsSavedImmediately_withoutWaitingOnNetwork() = runUnitTest {
        // Avatar-only edits can't fail validation in a way the user
        // needs to fix before leaving the screen, so we stay optimistic
        // and let them navigate while the request lands.
        val gate = CompletableDeferred<UpdateProfileOutcome>()
        val profile = GatedUpdateProfile(gate)
        val vm = EditProfileViewModel(
            profileRepository = profile,
            inventoryRepository = NoOpInventoryRepository,
            appScope = AppCoroutineScope(dispatchers),
        )
        runCurrent()

        vm.eventFlow.test {
            vm.takeAction(EditProfileAction.AvatarSelected("🦊"))
            vm.takeAction(EditProfileAction.Submit)

            assertEquals(EditProfileEvent.Saved, awaitItem())
            assertEquals(
                1, profile.updateStarted,
                "update should be in-flight while Saved already emitted",
            )
            assertEquals(
                0, profile.updateFinished,
                "avatar-only Save must not block on the network roundtrip",
            )

            gate.complete(UpdateProfileOutcome.Success(sampleProfile))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun submit_nameChanged_waitsForServer_andSurfacesDisplayNameTakenInline() = runUnitTest {
        // When the name changed we *await* the outcome so DisplayNameTaken
        // can land as an inline field error instead of a snackbar that
        // would only fire after the user already navigated away.
        val gate = CompletableDeferred<UpdateProfileOutcome>()
        val profile = GatedUpdateProfile(gate)
        val vm = EditProfileViewModel(
            profileRepository = profile,
            inventoryRepository = NoOpInventoryRepository,
            appScope = AppCoroutineScope(dispatchers),
        )
        runCurrent()

        vm.eventFlow.test {
            vm.takeAction(EditProfileAction.DisplayNameChanged("TakenName"))
            vm.takeAction(EditProfileAction.Submit)
            runCurrent()

            // No Saved yet — the VM is awaiting the server.
            expectNoEvents()
            assertTrue(vm.state.isSubmitting, "should still be in-flight")
            assertNull(vm.state.displayNameError)

            gate.complete(UpdateProfileOutcome.DisplayNameTaken)
            runCurrent()

            expectNoEvents() // still no Saved — the validation failed.
            assertEquals(EditProfileDisplayNameError.Taken, vm.state.displayNameError)
            assertEquals(false, vm.state.isSubmitting)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun submit_nameChanged_invalidName_surfacesInvalidInline() = runUnitTest {
        // Sibling to the DisplayNameTaken case — InvalidDisplayName is the
        // server's other inline-rejection variant (e.g. punctuation,
        // disallowed character class). Same await-and-surface treatment.
        val gate = CompletableDeferred<UpdateProfileOutcome>()
        val profile = GatedUpdateProfile(gate)
        val vm = EditProfileViewModel(
            profileRepository = profile,
            inventoryRepository = NoOpInventoryRepository,
            appScope = AppCoroutineScope(dispatchers),
        )
        runCurrent()

        vm.eventFlow.test {
            vm.takeAction(EditProfileAction.DisplayNameChanged("Bad!Name"))
            vm.takeAction(EditProfileAction.Submit)
            runCurrent()

            gate.complete(UpdateProfileOutcome.InvalidDisplayName)
            runCurrent()

            expectNoEvents()
            assertEquals(EditProfileDisplayNameError.Invalid, vm.state.displayNameError)
            assertEquals(false, vm.state.isSubmitting)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun submit_nameChanged_success_emitsSavedAfterServerAck() = runUnitTest {
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
            vm.takeAction(EditProfileAction.Submit)
            runCurrent()
            expectNoEvents()

            gate.complete(UpdateProfileOutcome.Success(sampleProfile))

            assertEquals(EditProfileEvent.Saved, awaitItem())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun displayNameChanged_clearsPreviousDisplayNameError() = runUnitTest {
        // Editing the field after a "taken" rejection should clear the
        // inline error so the user isn't staring at it while typing
        // their next attempt.
        val gate = CompletableDeferred<UpdateProfileOutcome>()
        val profile = GatedUpdateProfile(gate)
        val vm = EditProfileViewModel(
            profileRepository = profile,
            inventoryRepository = NoOpInventoryRepository,
            appScope = AppCoroutineScope(dispatchers),
        )
        runCurrent()

        vm.takeAction(EditProfileAction.DisplayNameChanged("Taken"))
        vm.takeAction(EditProfileAction.Submit)
        runCurrent()
        gate.complete(UpdateProfileOutcome.DisplayNameTaken)
        runCurrent()
        assertNotNull(vm.state.displayNameError, "precondition: error is set")

        vm.takeAction(EditProfileAction.DisplayNameChanged("Different"))
        runCurrent()

        assertNull(vm.state.displayNameError)
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
