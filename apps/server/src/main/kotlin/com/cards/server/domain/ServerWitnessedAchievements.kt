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
 * Scope is deliberately the *count-based* slice only (today: `HANDS_100_MP`).
 * The per-hand-shape MP ids (busts, win-by-fold, double/triple-up, pot-size)
 * need richer signals than a raw count and stay unevaluated until those
 * signals exist; bot-mode achievements stay client self-grant.
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
