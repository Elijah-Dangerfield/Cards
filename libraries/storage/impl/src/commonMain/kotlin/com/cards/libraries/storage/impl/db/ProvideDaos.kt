package com.dangerfield.cards.libraries.storage.impl.db

import com.dangerfield.cards.libraries.cards.storage.db.SessionDao
import com.dangerfield.cards.libraries.cards.storage.db.UserDao
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = UserDao::class)
class ProvideUserDao @Inject constructor(
    provider: AppDatabaseProvider
) : UserDao by provider.database.userDao()

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = SessionDao::class)
class ProvideSessionDao @Inject constructor(
    provider: AppDatabaseProvider
) : SessionDao by provider.database.sessionDao()
