package com.dangerfield.cards.libraries.storage.impl.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import com.dangerfield.cards.libraries.cards.storage.db.AchievementCounterEntity
import com.dangerfield.cards.libraries.cards.storage.db.AchievementDao
import com.dangerfield.cards.libraries.cards.storage.db.AchievementEarnedEntity
import com.dangerfield.cards.libraries.cards.storage.db.ChipsDao
import com.dangerfield.cards.libraries.cards.storage.db.ChipsEntity
import com.dangerfield.cards.libraries.cards.storage.db.EquipmentDao
import com.dangerfield.cards.libraries.cards.storage.db.EquipmentEntity
import com.dangerfield.cards.libraries.cards.storage.db.InventoryDao
import com.dangerfield.cards.libraries.cards.storage.db.InventoryEntity
import com.dangerfield.cards.libraries.cards.storage.db.ProgressionDao
import com.dangerfield.cards.libraries.cards.storage.db.ProgressionEntity
import com.dangerfield.cards.libraries.cards.storage.db.SessionDao
import com.dangerfield.cards.libraries.cards.storage.db.SessionEntity
import com.dangerfield.cards.libraries.cards.storage.db.UserDao
import com.dangerfield.cards.libraries.cards.storage.db.UserEntity
import com.dangerfield.cards.libraries.cards.storage.db.XpEventDao
import com.dangerfield.cards.libraries.cards.storage.db.XpEventEntity

@Database(
    entities = [
        UserEntity::class,
        SessionEntity::class,
        ProgressionEntity::class,
        XpEventEntity::class,
        ChipsEntity::class,
        AchievementEarnedEntity::class,
        AchievementCounterEntity::class,
        InventoryEntity::class,
        EquipmentEntity::class,
    ],
    version = 10, // v10: equipment table for equipped/unequipped intent + sync state
    exportSchema = true
)
@TypeConverters(CoreTypeConverters::class)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun sessionDao(): SessionDao
    abstract fun progressionDao(): ProgressionDao
    abstract fun xpEventDao(): XpEventDao
    abstract fun chipsDao(): ChipsDao
    abstract fun achievementDao(): AchievementDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun equipmentDao(): EquipmentDao
}

@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
