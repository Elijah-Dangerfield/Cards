package com.dangerfield.cards.libraries.storage.impl.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.dangerfield.cards.libraries.cards.storage.db.AchievementCounterEntity
import com.dangerfield.cards.libraries.cards.storage.db.AchievementDao
import com.dangerfield.cards.libraries.cards.storage.db.AchievementEarnedEntity
import com.dangerfield.cards.libraries.cards.storage.db.ChipsDao
import com.dangerfield.cards.libraries.cards.storage.db.ChipsEntity
import com.dangerfield.cards.libraries.cards.storage.db.EquipmentDao
import com.dangerfield.cards.libraries.cards.storage.db.EquipmentEntity
import com.dangerfield.cards.libraries.cards.storage.db.InventoryDao
import com.dangerfield.cards.libraries.cards.storage.db.InventoryEntity
import com.dangerfield.cards.libraries.cards.storage.db.PlayStyleDao
import com.dangerfield.cards.libraries.cards.storage.db.PlayStyleEntity
import com.dangerfield.cards.libraries.cards.storage.db.PlayStyleEventDao
import com.dangerfield.cards.libraries.cards.storage.db.PlayStyleEventEntity
import com.dangerfield.cards.libraries.cards.storage.db.ProgressionDao
import com.dangerfield.cards.libraries.cards.storage.db.ProgressionEntity
import com.dangerfield.cards.libraries.cards.storage.db.UserMessageDao
import com.dangerfield.cards.libraries.cards.storage.db.UserMessageEntity
import com.dangerfield.cards.libraries.cards.storage.db.WalletEventDao
import com.dangerfield.cards.libraries.cards.storage.db.WalletEventEntity
import com.dangerfield.cards.libraries.cards.storage.db.XpEventDao
import com.dangerfield.cards.libraries.cards.storage.db.XpEventEntity

@Database(
    entities = [
        ProgressionEntity::class,
        XpEventEntity::class,
        ChipsEntity::class,
        AchievementEarnedEntity::class,
        AchievementCounterEntity::class,
        InventoryEntity::class,
        EquipmentEntity::class,
        WalletEventEntity::class,
        UserMessageEntity::class,
        PlayStyleEntity::class,
        PlayStyleEventEntity::class,
    ],
    version = 19, // v19: play_style + play_style_events (human play-style outbox + cache)
    exportSchema = true
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun progressionDao(): ProgressionDao
    abstract fun xpEventDao(): XpEventDao
    abstract fun playStyleDao(): PlayStyleDao
    abstract fun playStyleEventDao(): PlayStyleEventDao
    abstract fun chipsDao(): ChipsDao
    abstract fun achievementDao(): AchievementDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun equipmentDao(): EquipmentDao
    abstract fun walletEventDao(): WalletEventDao
    abstract fun userMessageDao(): UserMessageDao
}

@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
