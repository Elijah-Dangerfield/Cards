package com.dangerfield.cards.libraries.storage.impl.db

import androidx.sqlite.SQLiteDriver

interface SQLiteDriverFactory {
    fun create(): SQLiteDriver
}
