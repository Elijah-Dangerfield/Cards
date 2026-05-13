package com.dangerfield.cards.libraries.storage.impl.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RealAppDatabaseProvider @Inject constructor(
    private val builderFactory: AppDatabaseBuilderFactory,
    private val dispatcherProvider: DispatcherProvider
) : AppDatabaseProvider {

    private val converters = CoreTypeConverters()

    override val database: AppDatabase by lazy {
        builderFactory
            .create()
            .addTypeConverter(converters)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(dispatcherProvider.io)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
}
