package com.dangerfield.cards.libraries.cards.storage.db

interface ClearableDao {
    suspend fun deleteAll()
}
