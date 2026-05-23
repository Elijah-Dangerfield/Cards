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

    override val database: AppDatabase by lazy {
        // No .addTypeConverter call: the v14 schema cleanup (commit
        // 4aa6097, "identity stack cleanup") dropped UserEntity +
        // SessionEntity, which were the only entities using
        // CoreTypeConverters' Instant / List / Map converters. Every
        // remaining entity uses primitive Room types (Long for
        // timestamps, etc.), so Room's generated code returns
        // emptyList() from every DAO's getRequiredConverters() — and
        // passing an unneeded converter instance trips Room's
        // builder.build() validation. If a future entity needs a
        // converter again, re-add it here.
        builderFactory
            .create()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(dispatcherProvider.io)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
}
