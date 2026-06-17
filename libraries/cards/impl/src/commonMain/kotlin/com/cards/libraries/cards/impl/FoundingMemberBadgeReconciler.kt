package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.CosmeticSlot
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.cosmeticSlotFor
import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The founding-member badge is granted to inventory on signup but never
 * auto-equipped, so owners would have to dig into My Items to surface their
 * 🏛 — and it'd show nowhere (table, Player Card) until they did. Founding
 * status is an earned honor, not a styling choice, so when an owner has the
 * badge AND their badge slot is empty, equip it for them.
 *
 * Only fills an *empty* slot: if the user has deliberately equipped a
 * different badge, we leave it. Idempotent and self-terminating — once
 * equipped, [EquipmentRepository.observeEquipped] reports the badge and the
 * condition stops firing.
 *
 * Boots via [AutoInit] and rides the existing inventory/equipment sync (which
 * refreshes on sign-in + foreground), so a freshly-granted badge equips as
 * soon as the inventory row lands.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
@Inject
class FoundingMemberBadgeReconciler(
    private val inventoryRepository: InventoryRepository,
    private val equipmentRepository: EquipmentRepository,
    private val appScope: AppCoroutineScope,
) : AutoInit {

    init {
        appScope.launch {
            combine(
                inventoryRepository.observeInventory(),
                equipmentRepository.observeEquipped(),
            ) { inventory, equipped ->
                val owns = inventory.any { it.productId == FOUNDING_MEMBER_BADGE_PRODUCT_ID }
                val badgeSlotFree = equipped.none { cosmeticSlotFor(it.productId) == CosmeticSlot.Badge }
                owns && badgeSlotFree
            }
                .distinctUntilChanged()
                .collect { shouldEquip ->
                    if (shouldEquip) {
                        Catching { equipmentRepository.equip(FOUNDING_MEMBER_BADGE_PRODUCT_ID) }
                    }
                }
        }
    }
}

private const val FOUNDING_MEMBER_BADGE_PRODUCT_ID = "badge_founding_member_1000"
