package com.dangerfield.cards.libraries.storage.impl.db

import com.dangerfield.cards.libraries.cards.storage.db.AchievementDao
import com.dangerfield.cards.libraries.cards.storage.db.ChipsDao
import com.dangerfield.cards.libraries.cards.storage.db.ProgressionDao
import com.dangerfield.cards.libraries.cards.storage.db.SessionDao
import com.dangerfield.cards.libraries.cards.storage.db.UserDao
import com.dangerfield.cards.libraries.cards.storage.db.XpEventDao
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

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = ProgressionDao::class)
class ProvideProgressionDao @Inject constructor(
    provider: AppDatabaseProvider
) : ProgressionDao by provider.database.progressionDao()

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = XpEventDao::class)
class ProvideXpEventDao @Inject constructor(
    provider: AppDatabaseProvider
) : XpEventDao by provider.database.xpEventDao()

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = ChipsDao::class)
class ProvideChipsDao @Inject constructor(
    provider: AppDatabaseProvider
) : ChipsDao by provider.database.chipsDao()

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AchievementDao::class)
class ProvideAchievementDao @Inject constructor(
    provider: AppDatabaseProvider
) : AchievementDao by provider.database.achievementDao()
