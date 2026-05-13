package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Query("SELECT * FROM user WHERE id = 'user' LIMIT 1")
    fun observeUser(): Flow<UserEntity?>

    @Query("SELECT * FROM user WHERE id = 'user' LIMIT 1")
    suspend fun getUser(): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: UserEntity)

    @Update
    suspend fun update(user: UserEntity)

    @Query("UPDATE user SET name = :name WHERE id = 'user'")
    suspend fun updateName(name: String?)

    @Query("UPDATE user SET sessions_count = sessions_count + 1, last_session_at = :timestamp WHERE id = 'user'")
    suspend fun incrementSessionCount(timestamp: Long)

    @Query("UPDATE user SET app_open_count = app_open_count + 1 WHERE id = 'user'")
    suspend fun incrementAppOpenCount()

    @Query("UPDATE user SET has_completed_onboarding = 1 WHERE id = 'user'")
    suspend fun setOnboardingComplete()

    @Query("UPDATE user SET shake_count = shake_count + 1 WHERE id = 'user'")
    suspend fun incrementShakeCount()

    @Query("DELETE FROM user")
    suspend fun deleteAll()
}
