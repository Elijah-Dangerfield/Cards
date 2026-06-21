package com.dangerfield.cards.features.profile.impl.edit

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.EquipmentEntry
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.EquipmentToggleResult
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.DefaultLevelCurve
import com.dangerfield.cards.libraries.cards.LevelCurve
import com.dangerfield.cards.libraries.cards.LevelReward
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionConfig
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.PurchaseState
import com.dangerfield.cards.libraries.cards.RedeemResult
import com.dangerfield.cards.libraries.cards.XpEvent
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditProfileViewModelTest : CoroutineTest() {

    private fun buildVm(profile: ProfileRepository): EditProfileViewModel = EditProfileViewModel(
        profileRepository = profile,
        inventoryRepository = NoOpInventoryRepository,
        equipmentRepository = NoOpEquipmentRepository,
        progressionRepository = NoOpProgressionRepository,
        progressionConfig = NoOpProgressionConfig,
        productsRepository = NoOpProductsRepository,
        appScope = AppCoroutineScope(dispatchers),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun submit_updateProfileCallSurvivesViewModelTeardown() = runUnitTest {
        val gate = CompletableDeferred<UpdateProfileOutcome>()
        val profile = GatedUpdateProfile(gate)
        val vm = EditProfileViewModel(
            profileRepository = profile,
            inventoryRepository = NoOpInventoryRepository,
            equipmentRepository = NoOpEquipmentRepository,
            progressionRepository = NoOpProgressionRepository,
            progressionConfig = NoOpProgressionConfig,
            productsRepository = NoOpProductsRepository,
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
            equipmentRepository = NoOpEquipmentRepository,
            progressionRepository = NoOpProgressionRepository,
            progressionConfig = NoOpProgressionConfig,
            productsRepository = NoOpProductsRepository,
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
    fun avatarPacks_excludeUnownedPremium_unlockByLocalInventoryBeforeSync() = runUnitTest {
        // Contract: the picker is a wardrobe — only owned/unlocked packs
        // appear in `avatarPacks`. Unowned premium packs are excluded
        // (discovery moves to the Shop via `hasLockedAvatarPacks`). The
        // invariant pinned here is that an optimistic redeem flips the
        // pack into the picker off the local-inventory flow, without
        // waiting on the server sync (Pending row → live flow → next tick).
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
            equipmentRepository = NoOpEquipmentRepository,
            progressionRepository = NoOpProgressionRepository,
            progressionConfig = NoOpProgressionConfig,
            productsRepository = NoOpProductsRepository,
            appScope = AppCoroutineScope(dispatchers),
        )
        runCurrent()

        // Before any inventory: only the starter pack is pickable; the
        // unowned premium pack is excluded but flagged for the shop link.
        assertEquals(listOf("starter"), vm.state.avatarPacks.map { it.id })
        assertTrue(vm.state.hasLockedAvatarPacks)

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
            "owned premium pack must enter the picker on the local-inventory " +
                "tick, without waiting for server sync",
        )
        assertFalse(
            vm.state.hasLockedAvatarPacks,
            "shop link hides once every premium pack is owned",
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun avatarPacks_onlyOwnedPacks_serverOrderPreserved() = runUnitTest {
        // The picker shows only what the user can actually pick — Starter +
        // any owned premium packs — in server order. Unowned premium packs
        // are excluded (they live in the Shop). For
        // [Starter, Animals(unowned), Food(owned), Fantasy(unowned)] the
        // picker renders [Starter, Food] and flags the shop link.
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
            equipmentRepository = NoOpEquipmentRepository,
            progressionRepository = NoOpProgressionRepository,
            progressionConfig = NoOpProgressionConfig,
            productsRepository = NoOpProductsRepository,
            appScope = AppCoroutineScope(dispatchers),
        )
        runCurrent()

        assertEquals(
            listOf("starter", "food"),
            vm.state.avatarPacks.map { it.id },
            "only owned packs render, in server order",
        )
        assertTrue(vm.state.hasLockedAvatarPacks)
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
            equipmentRepository = NoOpEquipmentRepository,
            progressionRepository = NoOpProgressionRepository,
            progressionConfig = NoOpProgressionConfig,
            productsRepository = NoOpProductsRepository,
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

    @Test
    fun seedsFromFallback_whenOfflineOnboardedIdentityPresent() = runUnitTest {
        // Ungated: an offline-onboarded user (Fallback carrying their chosen
        // identity) can open Edit Profile and the form pre-fills from it.
        val gate = CompletableDeferred<UpdateProfileOutcome>()
        val profile = GatedUpdateProfile(
            gate,
            seedProfile = Profile.Fallback(
                id = "local-1",
                displayName = "Foxy",
                avatarEmoji = "🦊",
                avatarBackgroundColor = "#ff6b35",
            ),
        )
        val vm = buildVm(profile)
        runCurrent()

        assertEquals("Foxy", vm.state.displayName)
        assertEquals("🦊", vm.state.selectedAvatarEmoji)
        assertEquals("#ff6b35", vm.state.selectedAvatarBackgroundColor)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun submit_queuedOffline_emitsSaved() = runUnitTest {
        // A session-less edit returns Queued (applied locally, syncs later); the
        // VM treats it like a save and lets the user leave.
        val gate = CompletableDeferred<UpdateProfileOutcome>()
        val profile = GatedUpdateProfile(
            gate,
            seedProfile = Profile.Fallback(
                id = "local-1",
                displayName = "Foxy",
                avatarEmoji = "🦊",
                avatarBackgroundColor = null,
            ),
        )
        val vm = buildVm(profile)
        runCurrent()

        vm.eventFlow.test {
            vm.takeAction(EditProfileAction.DisplayNameChanged("Fox2"))
            vm.takeAction(EditProfileAction.Submit)
            runCurrent()
            gate.complete(UpdateProfileOutcome.Queued)
            assertEquals(EditProfileEvent.Saved, awaitItem())
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
            equipmentRepository = NoOpEquipmentRepository,
            progressionRepository = NoOpProgressionRepository,
            progressionConfig = NoOpProgressionConfig,
            productsRepository = NoOpProductsRepository,
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
            equipmentRepository = NoOpEquipmentRepository,
            progressionRepository = NoOpProgressionRepository,
            progressionConfig = NoOpProgressionConfig,
            productsRepository = NoOpProductsRepository,
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
            equipmentRepository = NoOpEquipmentRepository,
            progressionRepository = NoOpProgressionRepository,
            progressionConfig = NoOpProgressionConfig,
            productsRepository = NoOpProductsRepository,
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
            equipmentRepository = NoOpEquipmentRepository,
            progressionRepository = NoOpProgressionRepository,
            progressionConfig = NoOpProgressionConfig,
            productsRepository = NoOpProductsRepository,
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
    private val seedProfile: Profile = sampleProfile,
) : ProfileRepository {
    var updateStarted: Int = 0
        private set
    var updateFinished: Int = 0
        private set
    var fetchAvatarPackCalls: Int = 0
        private set

    private val flow = MutableSharedFlow<Profile>(replay = 1, extraBufferCapacity = 1).apply {
        tryEmit(seedProfile)
    }

    override suspend fun current(): Profile = seedProfile
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

private object NoOpProductsRepository : com.dangerfield.cards.libraries.products.ProductsRepository {
    override fun observeCatalog(): Flow<com.dangerfield.cards.libraries.products.ProductCatalog> =
        flowOf(com.dangerfield.cards.libraries.products.ProductCatalog.Empty)
    override suspend fun refresh(force: Boolean): Result<com.dangerfield.cards.libraries.products.ProductCatalog> =
        Result.success(com.dangerfield.cards.libraries.products.ProductCatalog.Empty)
    override fun observeTimeAnchor(): Flow<com.dangerfield.cards.libraries.products.CatalogTimeAnchor?> = flowOf(null)
    override fun observeIsRefreshing(): Flow<Boolean> = flowOf(false)
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

private object NoOpEquipmentRepository : EquipmentRepository {
    override fun observeEquipped(): Flow<List<EquipmentEntry>> = flowOf(emptyList())
    override suspend fun getAll(): List<EquipmentEntry> = emptyList()
    override suspend fun equip(productId: String): EquipmentToggleResult = error("unused")
    override suspend fun unequip(productId: String): EquipmentToggleResult = error("unused")
    override suspend fun applyServerSnapshot(authoritative: List<EquipmentEntry>) = Unit
    override suspend fun dropOrphanEquipment(ownedProductIds: Set<String>): List<String> = emptyList()
    override suspend fun deleteAll() = Unit
    override suspend fun sync(): Result<Unit> = Result.success(Unit)
}

private object NoOpProgressionRepository : ProgressionRepository {
    override fun observeProgression(): Flow<Progression> = flowOf(Progression.Empty)
    override suspend fun getProgression(): Progression = Progression.Empty
    override suspend fun awardForHand(summary: HandResultSummary): List<XpEvent> = error("unused")
    override suspend fun applyAchievementXp(delta: Int, description: String?): XpEvent = error("unused")
    override suspend fun sync(): Result<Unit> = Result.success(Unit)
    override suspend fun deleteAll() = Unit
    override suspend fun debugSetTotalXp(totalXp: Long) = Unit
}

private object NoOpProgressionConfig : ProgressionConfig {
    override fun rewardsForLevel(level: Int): List<LevelReward> = emptyList()
    override fun levelCurve(): LevelCurve = DefaultLevelCurve
}

