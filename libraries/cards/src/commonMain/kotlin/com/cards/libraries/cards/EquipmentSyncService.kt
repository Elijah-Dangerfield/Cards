package com.dangerfield.cards.libraries.cards

/**
 * Reconciles local equipment intent with the server's authoritative
 * snapshot. Mirrors [InventorySyncService] in shape so the same retry
 * mental model applies.
 *
 * Lifecycle:
 *  - Fired by an app-launch hook + foreground resume via
 *    [com.dangerfield.cards.libraries.cards.impl.EquipmentSyncBootstrapper].
 *  - One in-flight sync at a time (mutex-guarded inside the impl).
 *
 * Empty pending set is still worth a call on cold boot — that's how we
 * pull the server's current snapshot to apply locally (so equip-from-
 * device-A shows up on device-B after sign-in).
 *
 * Failure modes:
 *  - Network error: leaves pending rows Pending. Next launch retries.
 *  - Server 5xx: same as network error.
 *  - Auth error: still leaves them Pending — user might re-auth.
 */
interface EquipmentSyncService {
    suspend fun sync(): Result<Unit>
}
