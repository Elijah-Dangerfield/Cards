package com.dangerfield.cards.libraries.storage.impl.db

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import me.tatarka.inject.annotations.Inject
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@OptIn(ExperimentalForeignApi::class)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosAppDatabaseBuilderFactory @Inject constructor() : AppDatabaseBuilderFactory {

    override fun create(): RoomDatabase.Builder<AppDatabase> {
        val documentsUrl = requireNotNull(
            NSFileManager.defaultManager.URLForDirectory(
                directory = NSDocumentDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null
            )
        )
        val dbUrl = documentsUrl.URLByAppendingPathComponent("cards.db")
        val dbPath = requireNotNull(dbUrl?.path)
        return Room.databaseBuilder<AppDatabase>(
            name = dbPath
        )
    }
}
