package com.dangerfield.cards.libraries.storage.impl.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidAppDatabaseBuilderFactory @Inject constructor(
    private val context: Context
) : AppDatabaseBuilderFactory {

    override fun create(): RoomDatabase.Builder<AppDatabase> {
        return Room.databaseBuilder<AppDatabase>(
            name = "cards.db",
            context = context,
        )
    }
}
