package com.dangerfield.cards.libraries.storage.impl.db

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidSQLiteDriverFactory @Inject constructor() : SQLiteDriverFactory {

    // The bundled driver is primary because it pins one SQLite version across every
    // OS level. The platform driver gives that up in exchange for an app that opens
    // at all when Play delivers an install without the ABI split (ENG-72), which is
    // why FallbackSQLiteDriver reports the switch instead of making it quietly.
    override fun create(): SQLiteDriver = FallbackSQLiteDriver(
        primary = BundledSQLiteDriver(),
        fallback = AndroidSQLiteDriver(),
    )
}
