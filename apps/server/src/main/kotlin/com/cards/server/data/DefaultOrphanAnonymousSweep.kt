package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.DeleteUserResult
import com.dangerfield.cards.server.domain.OrphanAnonymousSweep
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.SupabaseAdminClient
import com.dangerfield.cards.server.domain.SweepResult
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.plugins.ServerMetrics
import kotlinx.coroutines.CancellationException
import me.tatarka.inject.annotations.Inject
import org.slf4j.LoggerFactory
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * Lists anon users older than the configured TTL, verifies each against the
 * shared never-delete-progress guards ([OrphanCandidateVerifier] — IAP
 * spend, engagement inventory, meaningful XP, active room seat), then
 * deletes the survivors one by one via [SupabaseAdminClient.deleteUser] +
 * [ProfileRepository.delete].
 *
 * Per-user error handling: a single failed delete doesn't stop the
 * sweep — it logs + bumps the failure count and moves on. The next
 * sweep retries. This keeps a transient Supabase 5xx from blocking the
 * happy path.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class DefaultOrphanAnonymousSweep(
    private val adminClient: SupabaseAdminClient,
    private val profileRepository: ProfileRepository,
    private val verifier: OrphanCandidateVerifier,
    private val clock: Clock,
) : OrphanAnonymousSweep {

    private val logger = LoggerFactory.getLogger(DefaultOrphanAnonymousSweep::class.java)

    override suspend fun run(maxInactiveAge: Duration): SweepResult {
        val threshold = clock.now() - maxInactiveAge
        val candidates = adminClient.listAnonymousUsersOlderThan(threshold)
        val ttlDays = maxInactiveAge.inWholeDays

        // listAnonymousUsersOlderThan returns empty when service-role isn't
        // set, but we can't tell that from the result alone. Probe with a
        // dummy delete to surface NotConfigured cleanly.
        if (candidates.isEmpty()) {
            val probe = adminClient.deleteUser(UserId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000000")))
            if (probe is DeleteUserResult.NotConfigured) {
                logger.warn("Anonymous sweep skipped: SUPABASE_SERVICE_ROLE_KEY is not set")
                return SweepResult(candidatesFound = 0, deleted = 0, failedToDelete = 0, notConfigured = true)
            }
            ServerMetrics.recordAnonOrphanCandidates(count = 0L, ttlDays = ttlDays)
            return SweepResult(candidatesFound = 0, deleted = 0, failedToDelete = 0, notConfigured = false)
        }
        ServerMetrics.recordAnonOrphanCandidates(count = candidates.size.toLong(), ttlDays = ttlDays)

        var deleted = 0
        var skipped = 0
        var failed = 0
        for (userId in candidates) {
            val skipReason = verifier.skipReason(userId)
            if (skipReason != null) {
                logger.info("Anonymous sweep preserved {}: {}", userId, skipReason)
                skipped++
                continue
            }
            val outcome = adminClient.deleteUser(userId)
            when (outcome) {
                DeleteUserResult.Success, DeleteUserResult.AlreadyGone -> {
                    try {
                        profileRepository.delete(userId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.warn("Profile delete failed after admin delete for {}; will retry next sweep", userId, e)
                    }
                    deleted++
                }
                DeleteUserResult.NotConfigured -> {
                    logger.warn("Anonymous sweep aborted mid-run: SUPABASE_SERVICE_ROLE_KEY is no longer set")
                    return SweepResult(
                        candidatesFound = candidates.size,
                        deleted = deleted,
                        skipped = skipped,
                        failedToDelete = failed,
                        notConfigured = true,
                    )
                }
                is DeleteUserResult.Failure -> {
                    logger.warn(
                        "Failed to delete orphan anon user {} (status={}); will retry next sweep",
                        userId, outcome.statusCode, outcome.cause,
                    )
                    failed++
                }
            }
        }
        logger.info(
            "Anonymous sweep complete: found={}, deleted={}, skipped={}, failed={}",
            candidates.size, deleted, skipped, failed,
        )
        return SweepResult(
            candidatesFound = candidates.size,
            deleted = deleted,
            skipped = skipped,
            failedToDelete = failed,
            notConfigured = false,
        )
    }
}
