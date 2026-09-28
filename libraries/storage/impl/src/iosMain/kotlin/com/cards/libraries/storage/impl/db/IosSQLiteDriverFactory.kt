package com.dangerfield.cards.libraries.storage.impl.db

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosSQLiteDriverFactory @Inject constructor() : SQLiteDriverFactory {

    // No fallback here: iOS links the bundled SQLite into the framework itself, so
    // there is no separately delivered native library to go missing.
    override fun create(): SQLiteDriver = BundledSQLiteDriver()
}
