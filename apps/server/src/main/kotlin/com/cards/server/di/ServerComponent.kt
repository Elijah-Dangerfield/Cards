package com.dangerfield.cards.server.di

import com.dangerfield.cards.server.config.BillingConfig
import com.dangerfield.cards.server.config.SupabaseConfig
import com.dangerfield.cards.server.data.RelaxedGrantRateLimiter
import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.domain.AchievementRepository
import com.dangerfield.cards.server.domain.AppConfigAdminRepository
import com.dangerfield.cards.server.domain.AppConfigManifestRepository
import com.dangerfield.cards.server.domain.AppConfigSource
import com.dangerfield.cards.server.domain.BillingRepository
import com.dangerfield.cards.server.domain.EquipmentRepository
import com.dangerfield.cards.server.domain.FriendRepository
import com.dangerfield.cards.server.domain.HandsFinishedRepository
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.ModerationRepository
import com.dangerfield.cards.server.domain.OrphanAnonymousSweep
import com.dangerfield.cards.server.domain.OrphanInstallSweep
import com.dangerfield.cards.server.domain.PlayStyleRepository
import com.dangerfield.cards.server.domain.PlayerReportRepository
import com.dangerfield.cards.server.domain.PlayerStatsRepository
import com.dangerfield.cards.server.domain.ProductCatalogSource
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.ReceiptValidator
import com.dangerfield.cards.server.domain.ProgressionRepository
import com.dangerfield.cards.server.domain.RecentOpponentsRepository
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.SupabaseAdminClient
import com.dangerfield.cards.server.domain.TableSessionRecoverySweep
import com.dangerfield.cards.server.domain.TableSessionService
import com.dangerfield.cards.server.domain.UserMessageRepository
import com.dangerfield.cards.server.domain.WalletRepository
import com.dangerfield.cards.server.game.GameSessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    @get:Provides val billingConfig: BillingConfig,
) {
    abstract val appConfigSource: AppConfigSource
    abstract val appConfigAdminRepository: AppConfigAdminRepository
    abstract val appConfigManifestRepository: AppConfigManifestRepository
    abstract val productCatalogSource: ProductCatalogSource
    abstract val profileRepository: ProfileRepository
    abstract val moderationRepository: ModerationRepository
    abstract val supabaseAdminClient: SupabaseAdminClient
    abstract val orphanAnonymousSweep: OrphanAnonymousSweep
    abstract val orphanInstallSweep: OrphanInstallSweep
    abstract val equipmentRepository: EquipmentRepository
    abstract val inventoryRepository: InventoryRepository
    abstract val walletRepository: WalletRepository
    abstract val billingRepository: BillingRepository
    abstract val receiptValidator: ReceiptValidator
    abstract val relaxedGrantRateLimiter: RelaxedGrantRateLimiter
    abstract val progressionRepository: ProgressionRepository
    abstract val playStyleRepository: PlayStyleRepository
    abstract val playerStatsRepository: PlayerStatsRepository
    abstract val achievementRepository: AchievementRepository
    abstract val handsFinishedRepository: HandsFinishedRepository
    abstract val friendRepository: FriendRepository
    abstract val recentOpponentsRepository: RecentOpponentsRepository
    abstract val playerReportRepository: PlayerReportRepository
    abstract val userMessageRepository: UserMessageRepository
    abstract val roomService: RoomService
    abstract val gameSessionRegistry: GameSessionRegistry
    abstract val tableSessionService: TableSessionService
    abstract val tableSessionRecoverySweep: TableSessionRecoverySweep

    /**
     * Wall-clock source. Singleton so every component sees the same "now"
     * within a request. Tests swap this for a fixed clock by passing
     * their own instance to repositories directly.
     */
    @Provides
    fun provideClock(): Clock = Clock.System

    /**
     * Long-lived application scope for server-owned background work (the
     * per-session bot drivers). SupervisorJob so one failure doesn't cascade;
     * Default dispatcher because the work is CPU-bound (bot Monte Carlo) with
     * short suspending pauses, never blocking I/O. Singleton — the process owns
     * exactly one and never cancels it (it dies with the process).
     */
    @Provides
    @SingleIn(ServerScope::class)
    fun provideServerCoroutineScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
