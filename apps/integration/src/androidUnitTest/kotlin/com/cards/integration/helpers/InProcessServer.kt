package com.cards.integration.helpers

import com.dangerfield.cards.server.data.InMemoryRoomService
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.UpdateProfileOutcome
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.game.DefaultGameSessionRegistry
import com.dangerfield.cards.server.game.NoOpSessionSnapshotStore
import com.dangerfield.cards.server.plugins.installAuthentication
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import com.dangerfield.cards.server.plugins.installWebSockets
import com.dangerfield.cards.server.routes.roomRoutes
import com.dangerfield.cards.server.routes.roomSocketRoutes
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A real Ktor server — the production room HTTP + WebSocket routes, in-memory
 * storage, real gameplay engine, test JWT auth — bound to an ephemeral port.
 *
 * Bound to a real port on purpose: the production client builds its own Ktor
 * engine and talks over real TCP, which Ktor's in-memory `testApplication`
 * engine can't serve. No Postgres/Docker — storage is in-memory and auth is
 * stubbed, so this runs as a plain host-JVM unit test.
 */
class InProcessServer : AutoCloseable {
    private val rooms = InMemoryRoomService(clock = Clock.System, random = Random(0))
    private val registry = DefaultGameSessionRegistry(NoOpSessionSnapshotStore(), Clock.System)

    private val server = embeddedServer(Netty, port = 0) {
        installSerialization()
        installStatusPages()
        installWebSockets()
        installAuthentication(IntegrationAuth.verification)
        routing {
            roomRoutes(rooms, FakeProfiles)
            roomSocketRoutes(
                rooms = rooms,
                gameSessions = registry,
                equipmentRepository = FakeEquipment,
                progressionRepository = FakeProgression,
            )
        }
    }.start(wait = false)

    /** The base URL a client's `NetworkConfig` should point at. */
    val baseUrl: String = runBlocking {
        "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
    }

    override fun close() {
        server.stop(0, 0)
    }
}

/** Server-side profile source — a deterministic display name per user. */
private object FakeProfiles : ProfileRepository {
    override suspend fun findById(userId: UserId): Profile? = null
    override suspend fun findOrCreate(userId: UserId): Profile = Profile(
        userId = userId,
        displayName = "P-${userId.value.toString().take(4)}",
        avatarEmoji = "🃏",
        avatarBackgroundColor = null,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    override suspend fun update(
        userId: UserId,
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): UpdateProfileOutcome = error("unused in the integration harness")

    override suspend fun delete(userId: UserId) = Unit
    override suspend fun touchInstallId(userId: UserId, installId: UUID): UUID? = null
    override suspend fun findInstallSiblings(installId: UUID, currentUserId: UserId): List<UserId> =
        emptyList()
}

/** No-op equipment source — the integration harness doesn't seat badges. */
private object FakeEquipment : com.dangerfield.cards.server.domain.EquipmentRepository {
    override suspend fun listEquipped(userId: UserId): List<com.dangerfield.cards.server.domain.EquippedItem> =
        emptyList()
    override suspend fun equip(
        userId: UserId,
        productId: String,
        newUpdatedAt: Instant,
    ): com.dangerfield.cards.server.domain.EquippedItem = error("unused in the integration harness")
    override suspend fun unequip(
        userId: UserId,
        productId: String,
        opUpdatedAt: Instant,
    ): com.dangerfield.cards.server.domain.EquippedItem? = null
}

/** No-op progression source — the integration harness doesn't seat opponent levels. */
private object FakeProgression : com.dangerfield.cards.server.domain.ProgressionRepository {
    override suspend fun findOrCreateResult(userId: UserId) = error("unused in the integration harness")
    override suspend fun find(userId: UserId): com.dangerfield.cards.server.domain.UserProgression? = null
    override suspend fun applyXp(
        userId: UserId,
        idempotencyKey: String,
        deltaXp: Long,
        source: String,
        mode: String,
        handId: String?,
        wasBoosted: Boolean,
    ) = error("unused in the integration harness")
    override suspend fun recentEvents(
        userId: UserId,
        limit: Int,
    ): List<com.dangerfield.cards.server.domain.XpEvent> = emptyList()
    override suspend fun deleteAllForUser(userId: UserId) = Unit
}
