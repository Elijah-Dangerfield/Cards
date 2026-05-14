package com.dangerfield.cards.libraries.storage.impl.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import com.dangerfield.cards.libraries.cards.storage.db.ChipsDao
import com.dangerfield.cards.libraries.cards.storage.db.ChipsEntity
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
    ],
    version = 6, // v6: added chips singleton table for shop / future MP buy-ins
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
}

@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
