package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressionDao : ClearableDao {

    @Query("SELECT * FROM progression WHERE id = 'user' LIMIT 1")
    fun observeProgression(): Flow<ProgressionEntity?>

    @Query("SELECT * FROM progression WHERE id = 'user' LIMIT 1")
    suspend fun getProgression(): ProgressionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(entity: ProgressionEntity)

    /**
     * Apply a hand's deltas in one atomic update. Counters are incremented;
     * `xpDelta` is added to `total_xp`. Always increments `hands_played`.
     */
    @Query(
        """
        UPDATE progression SET
            total_xp = total_xp + :xpDelta,
            hands_played = hands_played + 1,
            hands_won = hands_won + :handsWonDelta,
            hands_folded = hands_folded + :handsFoldedDelta,
            hands_lost_at_showdown = hands_lost_at_showdown + :handsLostAtShowdownDelta,
            bot_hands_played = bot_hands_played + :botHandsPlayedDelta,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE id = 'user'
        """
    )
    suspend fun applyHandDeltas(
        xpDelta: Int,
        handsWonDelta: Int,
        handsFoldedDelta: Int,
        handsLostAtShowdownDelta: Int,
        botHandsPlayedDelta: Int,
        updatedAtEpochMs: Long,
    )

    /**
     * Add `xpDelta` to `total_xp` without touching the hand counters — used
     * for non-hand XP awards (achievements, bonuses). Lazy-creates the row.
     */
    @Query(
        """
        UPDATE progression SET
            total_xp = total_xp + :xpDelta,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE id = 'user'
        """
    )
    suspend fun addXpOnly(xpDelta: Int, updatedAtEpochMs: Long)

    @Transaction
    suspend fun ensureExistsAndAddXp(xpDelta: Int, updatedAtEpochMs: Long) {
        insertIfMissing(ProgressionEntity())
        addXpOnly(xpDelta = xpDelta, updatedAtEpochMs = updatedAtEpochMs)
    }

    @Transaction
    suspend fun ensureExistsAndApply(
        xpDelta: Int,
        handsWonDelta: Int,
        handsFoldedDelta: Int,
        handsLostAtShowdownDelta: Int,
        botHandsPlayedDelta: Int,
        updatedAtEpochMs: Long,
    ) {
        insertIfMissing(ProgressionEntity())
        applyHandDeltas(
            xpDelta = xpDelta,
            handsWonDelta = handsWonDelta,
            handsFoldedDelta = handsFoldedDelta,
            handsLostAtShowdownDelta = handsLostAtShowdownDelta,
            botHandsPlayedDelta = botHandsPlayedDelta,
            updatedAtEpochMs = updatedAtEpochMs,
        )
    }

    /**
     * Overwrite `total_xp` to an absolute value. QA/debug only — production
     * code paths must go through `applyHandDeltas` / `addXpOnly` to keep the
     * ledger and counters consistent.
     */
    @Query(
        """
        UPDATE progression SET
            total_xp = :totalXp,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE id = 'user'
        """
    )
    suspend fun setTotalXp(totalXp: Long, updatedAtEpochMs: Long)

    @Transaction
    suspend fun ensureExistsAndSetTotalXp(totalXp: Long, updatedAtEpochMs: Long) {
        insertIfMissing(ProgressionEntity())
        setTotalXp(totalXp = totalXp, updatedAtEpochMs = updatedAtEpochMs)
    }

    @Query("DELETE FROM progression")
    override suspend fun deleteAll()
}
