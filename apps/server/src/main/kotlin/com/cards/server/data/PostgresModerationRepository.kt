package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.BanReason
import com.dangerfield.cards.server.domain.BanStatus
import com.dangerfield.cards.server.domain.ModerationRepository
import com.dangerfield.cards.server.domain.UserId
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.transactions.TransactionManager
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Reads ban standing off the native Supabase flag `auth.users.banned_until`.
 *
 * Raw SQL because the read crosses into Supabase's `auth` schema (which
 * Supabase owns and we don't model in Exposed) — same pattern as
 * [PostgresProfileRepository.findInstallSiblings]. A future timestamp means
 * blocked until then; null or a past timestamp means good standing.
 *
 * The native column carries no reason or appeal context, so every block this
 * repository reports is [BanReason.Banned]. A suspended-vs-banned split with
 * an appeal URL needs an app-level moderation table — out of this slice.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresModerationRepository(
    private val database: Database,
    private val clock: Clock,
) : ModerationRepository {

    override suspend fun banStatusFor(userId: UserId): BanStatus? = database.transaction {
        var bannedUntil: java.time.Instant? = null
        TransactionManager.current().exec(
            stmt = "SELECT banned_until FROM auth.users WHERE id = ?",
            args = listOf(org.jetbrains.exposed.sql.UUIDColumnType() to userId.value),
        ) { rs ->
            if (rs.next()) {
                bannedUntil = rs.getObject("banned_until", java.time.OffsetDateTime::class.java)
                    ?.toInstant()
            }
        }

        val until = bannedUntil?.toKotlinInstant() ?: return@transaction null
        if (until <= clock.now()) return@transaction null
        BanStatus(reason = BanReason.Banned, until = until)
    }
}
