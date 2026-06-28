package com.dangerfield.cards.features.upgrade

import com.dangerfield.cards.libraries.config.AppConfigMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pins the force-update + maintenance gate that `AppGuardGate` recomputes on
 * every streamed-config emission (ENG-6 / CARDS-4S).
 *
 * The gate is the live half of the cross-version rule: when a breaking
 * game-object change ships, the server raises `upgrade.minSupportedVersionCode`
 * above the running client's `VERSION_CODE`, and every client that re-resolves
 * the streamed config map must flip to [AppGuardState.UpgradeRequired] — the
 * app-wide blocking overlay `AppGuardLayer` draws *above the whole nav graph*,
 * including an in-session play screen.
 *
 * Because [AppGuardState.from] is a pure function of (config map, version code)
 * and `AppGuardGate` calls it on each `configStream` emission, "the overlay
 * raises live over the play screen" reduces to "a config map with a bumped
 * min-version yields UpgradeRequired" — there is no per-screen branch that could
 * leave the play surface uncovered. These cases assert exactly that, plus the
 * inverse (lowering it back drops the overlay) since the same recompute runs on
 * every emission.
 *
 * Cadence note: this proves the *reactive* path. A continuously-foregrounded
 * client only re-fetches config on a foreground transition (config is fetched
 * on foreground, never polled mid-session by deliberate design — see
 * `OfflineFirstAppConfigRepository`), so the overlay raises on the next
 * foreground within the throttle window, not while the user sits uninterrupted
 * mid-hand. That boundary is intended, not a gap to close here.
 */
class AppGuardStateTest {

    @Test
    fun belowBumpedMinVersion_raisesUpgradeRequired() {
        val state = AppGuardState.from(
            configMap = configOf("upgrade" to mapOf("minSupportedVersionCode" to 42)),
            clientVersionCode = 41,
        )
        assertIs<AppGuardState.UpgradeRequired>(state)
    }

    @Test
    fun atOrAboveMinVersion_isNormal() {
        assertEquals(
            AppGuardState.Normal,
            AppGuardState.from(
                configMap = configOf("upgrade" to mapOf("minSupportedVersionCode" to 42)),
                clientVersionCode = 42,
            ),
        )
    }

    @Test
    fun loweringMinVersionBackDownDropsOverlay() {
        val client = 41
        val blocked = AppGuardState.from(
            configMap = configOf("upgrade" to mapOf("minSupportedVersionCode" to 42)),
            clientVersionCode = client,
        )
        val cleared = AppGuardState.from(
            configMap = configOf("upgrade" to mapOf("minSupportedVersionCode" to 1)),
            clientVersionCode = client,
        )
        assertIs<AppGuardState.UpgradeRequired>(blocked)
        assertEquals(AppGuardState.Normal, cleared)
    }

    @Test
    fun upgradeRequiredOutranksMaintenance() {
        val state = AppGuardState.from(
            configMap = configOf(
                "upgrade" to mapOf(
                    "minSupportedVersionCode" to 99,
                    "maintenanceMode" to "blocking",
                    "maintenanceMessage" to "down for a bit",
                ),
            ),
            clientVersionCode = 1,
        )
        assertIs<AppGuardState.UpgradeRequired>(state)
    }

    @Test
    fun missingMinVersion_fallsBackToDefaultOne_isNormal() {
        assertEquals(
            AppGuardState.Normal,
            AppGuardState.from(configMap = configOf(), clientVersionCode = 1),
        )
    }

    @Test
    fun blockingMaintenance_whenVersionOk_blocksWithMessage() {
        val state = AppGuardState.from(
            configMap = configOf(
                "upgrade" to mapOf(
                    "maintenanceMode" to "blocking",
                    "maintenanceMessage" to "back in 15",
                ),
            ),
            clientVersionCode = 1,
        )
        assertEquals(AppGuardState.MaintenanceBlocking("back in 15"), state)
    }

    private fun configOf(vararg entries: Pair<String, Any>): AppConfigMap =
        object : AppConfigMap() {
            override val map: Map<String, *> = entries.toMap()
        }
}
