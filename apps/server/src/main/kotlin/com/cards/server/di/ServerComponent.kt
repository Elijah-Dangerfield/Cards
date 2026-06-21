package com.dangerfield.cards.server.di

import com.dangerfield.cards.server.config.SupabaseConfig
import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.domain.AchievementRepository
import com.dangerfield.cards.server.domain.AppConfigSource
import com.dangerfield.cards.server.domain.EquipmentRepository
import com.dangerfield.cards.server.domain.FriendRepository
import com.dangerfield.cards.server.domain.HandsFinishedRepository
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.OrphanAnonymousSweep
import com.dangerfield.cards.server.domain.OrphanInstallSweep
import com.dangerfield.cards.server.domain.ProductCatalogSource
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.ProgressionRepository
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.SupabaseAdminClient
import com.dangerfield.cards.server.domain.UserMessageRepository
import com.dangerfield.cards.server.domain.WalletRepository
import com.dangerfield.cards.server.game.GameSessionRegistry
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Root DI component for the server process. Mirrors the client's
 * `AppComponent` — anvil aggregates every `@ContributesBinding(ServerScope::class)`
 * on the classpath and KSP generates the merged implementation at compile time.
 *
 * Boot once at startup with the runtime config:
 *
 * ```
 * val component = ServerComponent::class.create(database, supabaseConfig)
 * routing { meRoutes(component.profileRepository, component.supabaseAdminClient) }
 * ```
 *
 * Add a new service: annotate its impl with `@ContributesBinding(ServerScope::class)`,
 * expose its interface as a property here, done.
 *
 * Constructor parameters are exposed via [@Provides] so repository impls can take
 * them as constructor parameters without a separate factory.
 */
@MergeComponent(ServerScope::class)
@SingleIn(ServerScope::class)
@OptIn(ExperimentalTime::class)
abstract class ServerComponent(
    @get:Provides val database: Database,
    @get:Provides val supabaseConfig: SupabaseConfig,
) {
    abstract val appConfigSource: AppConfigSource
    abstract val productCatalogSource: ProductCatalogSource
    abstract val profileRepository: ProfileRepository
    abstract val supabaseAdminClient: SupabaseAdminClient
    abstract val orphanAnonymousSweep: OrphanAnonymousSweep
    abstract val orphanInstallSweep: OrphanInstallSweep
    abstract val equipmentRepository: EquipmentRepository
    abstract val inventoryRepository: InventoryRepository
    abstract val walletRepository: WalletRepository
    abstract val progressionRepository: ProgressionRepository
    abstract val achievementRepository: AchievementRepository
    abstract val handsFinishedRepository: HandsFinishedRepository
    abstract val friendRepository: FriendRepository
    abstract val userMessageRepository: UserMessageRepository
    abstract val roomService: RoomService
    abstract val gameSessionRegistry: GameSessionRegistry

    /**
     * Wall-clock source. Singleton so every component sees the same "now"
     * within a request. Tests swap this for a fixed clock by passing
     * their own instance to repositories directly.
     */
    @Provides
    fun provideClock(): Clock = Clock.System
}
