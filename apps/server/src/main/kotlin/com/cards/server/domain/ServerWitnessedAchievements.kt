package com.dangerfield.cards.server.domain

/**
 * Server-side evaluation + grant of the *count-based* achievements in the
 * [ClientGrantableAchievements] `serverWitnessed` set.
 *
 * Those ids return `403` from the client grant route precisely because the
 * server witnesses them directly — a malicious client can't self-grant an MP
 * achievement it didn't earn. This is the counterpart that actually fires the
 * grant: after every server-resolved hand the registry asks this evaluator to
 * re-check the caller's [HandsFinishedRepository.countForUser] against the
 * count thresholds and, on a crossing, record the earned achievement and grant
 * the mapped cosmetic.
 *
 * Two evaluation paths:
 *  - [evaluate] drives the *count-based* ids off
 *    [HandsFinishedRepository.countForUser] (today: `FIRST_HAND_MP`,
 *    `HANDS_100_MP`).
 *  - [evaluateHand] drives the *per-hand-shape* one-shot ids off a single
 *    finished hand's [PlayerHandOutcome] (busts dealt, double/triple-up,
 *    pot-size). The cumulative per-hand ids (`BUST_DEALT_5_MP`,
 *    `WIN_BY_FOLD_10_MP`) need a durable per-user counter the server
 *    doesn't keep yet and stay unevaluated.
 *
 * Bot-mode achievements stay client self-grant.
 */
interface ServerWitnessedAchievements {

    /**
     * Re-evaluate the count-based server-witnessed achievements for [userId]
     * against their current finished-hand count, granting any newly crossed
     * thresholds. Idempotent — already-earned ids are skipped and the grant
     * paths underneath ([AchievementRepository.recordEarned] /
     * [InventoryRepository.recordEarnedGrant]) are first-write-wins, so a
     * re-evaluation on every finished hand is a safe no-op once granted.
     */
    suspend fun evaluate(userId: UserId)

    /**
     * Evaluate the one-shot per-hand-shape server-witnessed achievements for
     * [userId] against a single finished hand's [outcome], granting any whose
     * condition just fired. Idempotent for the same reason as [evaluate] —
     * already-earned ids are skipped and the grant is first-write-wins, so
     * re-deriving the outcome can never double-grant. Default no-op so test
     * doubles only need the path they exercise.
     */
    suspend fun evaluateHand(userId: UserId, outcome: PlayerHandOutcome) = Unit
}

/**
 * No-op stand-in mirroring [NoOpHandsFinishedRepository] — the default for
 * [com.dangerfield.cards.server.game.DefaultGameSessionRegistry] in unit code
 * that doesn't care about achievement grants. Production never sees it: the DI
 * graph resolves the real [ServerWitnessedAchievements] binding into the
 * registry's constructor.
 */
object NoOpServerWitnessedAchievements : ServerWitnessedAchievements {
    override suspend fun evaluate(userId: UserId) = Unit
}
